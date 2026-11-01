package dev.bytestorm.launcher.doctor;

import dev.bytestorm.api.service.Platforms;
import dev.bytestorm.cli.Diagnostics;
import dev.bytestorm.cli.Diagnostics.Finding;
import dev.bytestorm.cli.Diagnostics.Severity;
import dev.bytestorm.core.config.CloudConfig;
import dev.bytestorm.core.config.PortSettings;
import dev.bytestorm.launcher.layout.CloudLayout;
import dev.bytestorm.launcher.layout.ServiceDirectories;
import dev.bytestorm.node.template.ForwardingCheck;
import dev.bytestorm.node.template.TemplateLayers;
import dev.bytestorm.node.template.TemplatePreparer;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Prüft eine laufende Cloud und meldet, warum sie nicht so arbeitet wie erwartet
 * (§17).
 *
 * <p>Die Prüfungen decken die Fragen ab, die sonst erst im Backend-Log oder gar
 * nicht sichtbar werden: Sind alle Verzeichnisse da, passt der Java-Stand,
 * liegen Ports im Pool, antworten MongoDB und Redis, ist eine Adresse doppelt
 * belegt, sind Proxy-Gruppe und Fallback-Ziel überhaupt konfiguriert — und nutzen
 * Proxy und Backends dasselbe Forwarding-Secret (§14). Die
 * Klasse fasst nichts an: Sie liest Dateien und ruft die übergebenen
 * Verbindungsproben auf.
 */
public final class Doctor implements Diagnostics {

    /** Kleinste Java-Version, mit der Paper 1.21 startet. */
    public static final int MIN_JAVA = 21;

    /** Probe, die eine Datenbank anspricht und ihr Ergebnis beschreibt. */
    @FunctionalInterface
    public interface Probe {

        /**
         * @return Kurzbeschreibung, z. B. {@code antwortet in 12 ms}
         * @throws RuntimeException wenn die Verbindung fehlschlägt
         */
        String check() throws RuntimeException;
    }

    /**
     * Repariert einen Service ({@code doctor --fix}).
     *
     * <p>Die Prüfungen kennen nur Service-Namen; die Controller-Fassade arbeitet
     * mit IDs. Eine Implementierung darf beide Methoden bedienen — die
     * Standardausführung des Neustarts wendet nur die Templates an.
     */
    @FunctionalInterface
    public interface Repair {

        /**
         * @param service Service-Name
         * @throws RuntimeException wenn das erneute Anwenden fehlschlägt
         */
        void reapply(String service);

        /**
         * Stoppt den Service, wendet die Templates an und startet ihn neu. Nur so
         * übernimmt ein laufender Prozess die geänderten Dateien.
         *
         * @param service Service-Name
         * @throws RuntimeException wenn Stop, Anwenden oder Start fehlschlägt
         */
        default void restart(String service) {
            reapply(service);
        }
    }

    private final CloudLayout layout;
    private final Supplier<CloudConfig> config;
    private final Supplier<List<ServiceGroup>> groups;
    private final Supplier<List<ServiceInfo>> services;
    private final Probe mongo;
    private final Probe redis;
    private final Repair repair;

    /**
     * @param layout   Verzeichnislayout der Cloud
     * @param config   aktive Konfiguration
     * @param groups   Gruppen-Definitionen mit Plattform und Typ
     * @param services registrierte Services
     * @param mongo    Mongo-Prüfung
     * @param redis    Redis-Prüfung
     */
    public Doctor(CloudLayout layout, Supplier<CloudConfig> config,
                  Supplier<List<ServiceGroup>> groups, Supplier<List<ServiceInfo>> services,
                  Probe mongo, Probe redis) {
        this(layout, config, groups, services, mongo, redis, null);
    }

    /**
     * @param layout   Verzeichnislayout der Cloud
     * @param config   aktive Konfiguration
     * @param groups   Gruppen-Definitionen mit Plattform und Typ
     * @param services registrierte Services
     * @param mongo    Mongo-Prüfung
     * @param redis    Redis-Prüfung
     * @param repair   Reparatur für {@code --fix}, {@code null} wenn nicht verfügbar
     */
    public Doctor(CloudLayout layout, Supplier<CloudConfig> config,
                  Supplier<List<ServiceGroup>> groups, Supplier<List<ServiceInfo>> services,
                  Probe mongo, Probe redis, Repair repair) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.config = Objects.requireNonNull(config, "config");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.services = Objects.requireNonNull(services, "services");
        this.mongo = Objects.requireNonNull(mongo, "mongo");
        this.redis = Objects.requireNonNull(redis, "redis");
        this.repair = repair;
    }

    @Override
    public List<Finding> run() {
        List<Finding> findings = new ArrayList<>();
        CloudConfig current = config.get();
        Map<String, ServiceGroup> definitions = new HashMap<>();
        for (ServiceGroup group : groups.get()) {
            definitions.put(group.getName(), group);
        }
        List<ServiceInfo> known = services.get();
        paths(findings);
        templates(findings);
        java(findings, current);
        ports(findings, current, known);
        connection(findings, "MongoDB", mongo);
        connection(findings, "Redis", redis);
        addresses(findings, known);
        groups(findings, definitions);
        forwarding(findings, known, definitions, current.proxy().forwardingSecret());
        return List.copyOf(findings);
    }

    /**
     * Wendet die Templates auf die Services an, deren Forwarding-Dateien nicht
     * zur Konfiguration passen, und prüft anschließend erneut (§14, §17).
     *
     * <p>Ein laufender Service wird dafür neu gestartet: Er liest seine
     * Konfiguration nur beim Start, und ohne erneutes Anwenden bliebe ein schon
     * vorbereiteter statischer Ordner unverändert.
     */
    @Override
    public List<Finding> fix() {
        List<Finding> findings = new ArrayList<>();
        if (repair != null) {
            Map<String, ServiceGroup> definitions = new HashMap<>();
            for (ServiceGroup group : groups.get()) {
                definitions.put(group.getName(), group);
            }
            Map<String, ServiceInfo> broken = brokenForwarding(definitions);
            if (broken.isEmpty()) {
                findings.add(new Finding(Severity.OK, "Reparatur",
                        "keine Forwarding-Abweichung zu beheben"));
            }
            for (Map.Entry<String, ServiceInfo> entry : broken.entrySet()) {
                String name = entry.getKey();
                ServiceState state = entry.getValue().getState();
                if (state != ServiceState.STOPPED && state != ServiceState.CRASHED) {
                    // Ein laufender Service liest seine Dateien erst beim Start; nur
                    // Stop → Templates → Start bringt die Änderung in den Prozess.
                    try {
                        repair.restart(name);
                        findings.add(new Finding(Severity.OK, "Reparatur",
                                name + " neu gestartet (" + state + ") — Templates angewendet"));
                    } catch (RuntimeException e) {
                        findings.add(new Finding(Severity.WARNING, "Reparatur",
                                name + ": " + messageOf(e)));
                    }
                    continue;
                }
                try {
                    repair.reapply(name);
                    findings.add(new Finding(Severity.OK, "Reparatur",
                            name + ": Templates erneut angewendet"));
                } catch (RuntimeException e) {
                    findings.add(new Finding(Severity.WARNING, "Reparatur",
                            name + ": " + messageOf(e)));
                }
            }
        }
        findings.addAll(run());
        return List.copyOf(findings);
    }

    private void paths(List<Finding> findings) {
        List<String> missing = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (String directory : CloudLayout.DIRECTORIES) {
            Path path = layout.root().resolve(directory);
            if (!Files.isDirectory(path)) {
                missing.add(directory);
            } else if (!Files.isReadable(path)) {
                unreadable.add(directory);
            }
        }
        if (missing.isEmpty()) {
            findings.add(new Finding(Severity.OK, "Pfade",
                    CloudLayout.DIRECTORIES.size() + " Verzeichnisse vorhanden (" + layout.root() + ")"));
        } else {
            findings.add(new Finding(Severity.ERROR, "Pfade", "fehlen: " + String.join(", ", missing)));
        }
        if (!unreadable.isEmpty()) {
            findings.add(new Finding(Severity.ERROR, "Pfade", "nicht lesbar: " + String.join(", ", unreadable)));
        }
        if (!Files.isWritable(layout.root())) {
            findings.add(new Finding(Severity.ERROR, "Pfade",
                    "Cloud-Verzeichnis ist nicht beschreibbar: " + layout.root()));
        }
    }

    private void templates(List<Finding> findings) {
        List<String> required = List.of(
                TemplateLayers.PROXY + "/" + ForwardingCheck.VELOCITY_CONFIG,
                TemplateLayers.PROXY + "/" + ForwardingCheck.DEFAULT_SECRET_FILE,
                TemplateLayers.SERVER + "/" + ForwardingCheck.PAPER_CONFIG,
                TemplateLayers.SERVER + "/server.properties");
        List<String> missing = new ArrayList<>();
        for (String relative : required) {
            if (!Files.isRegularFile(layout.templates().resolve(relative))) {
                missing.add(relative);
            }
        }
        if (missing.isEmpty()) {
            findings.add(new Finding(Severity.OK, "Templates", "Proxy- und Server-Template vollständig"));
        } else {
            findings.add(new Finding(Severity.WARNING, "Templates",
                    "fehlen: " + String.join(", ", missing)
                            + " — neue Services starten ohne Port/Forwarding"));
        }
    }

    private void java(List<Finding> findings, CloudConfig current) {
        int feature = Runtime.version().feature();
        if (feature < MIN_JAVA) {
            findings.add(new Finding(Severity.ERROR, "Java",
                    "Cloud läuft mit Java " + feature + ", benötigt wird mindestens " + MIN_JAVA));
        } else {
            findings.add(new Finding(Severity.OK, "Java",
                    Runtime.version() + " (mindestens " + MIN_JAVA + " nötig)"));
        }
        if (current.runtime() != RuntimeType.PROCESS) {
            return;
        }
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        boolean executable = Files.isExecutable(bin.resolve("java")) || Files.isExecutable(bin.resolve("java.exe"));
        findings.add(executable
                ? new Finding(Severity.OK, "Java", "Service-Binary ausführbar: " + bin)
                : new Finding(Severity.ERROR, "Java", "Service-Binary nicht ausführbar: " + bin));
    }

    private void ports(List<Finding> findings, CloudConfig current, List<ServiceInfo> known) {
        PortSettings ports = current.ports();
        List<String> problems = new ArrayList<>();
        if (ports.proxy() < 1 || ports.proxy() > 65_535) {
            problems.add("Proxy-Port außerhalb 1–65535: " + ports.proxy());
        }
        if (ports.serverStart() < 1 || ports.serverStart() > 65_535) {
            problems.add("Server-Startport außerhalb 1–65535: " + ports.serverStart());
        }
        for (ServiceInfo info : known) {
            if (info.getPort() <= 0) {
                if (info.getState() == ServiceState.ONLINE) {
                    problems.add(info.getName() + " ist ONLINE, hat aber keinen Port");
                }
                continue;
            }
            if (info.getType() == ServiceType.PROXY) {
                if (info.getPort() != ports.proxy()) {
                    problems.add(info.getName() + " läuft auf " + info.getPort()
                            + ", konfiguriert ist " + ports.proxy());
                }
            } else if (info.getPort() < ports.serverStart()) {
                problems.add(info.getName() + " läuft auf " + info.getPort()
                        + ", der Pool beginnt bei " + ports.serverStart());
            }
        }
        if (problems.isEmpty()) {
            findings.add(new Finding(Severity.OK, "Ports",
                    "Proxy " + ports.proxy() + ", Server ab " + ports.serverStart()
                            + " — registrierte Ports passen"));
        } else {
            findings.add(new Finding(Severity.WARNING, "Ports", String.join("; ", problems)));
        }
    }

    private static void connection(List<Finding> findings, String area, Probe probe) {
        try {
            findings.add(new Finding(Severity.OK, area, probe.check()));
        } catch (RuntimeException e) {
            findings.add(new Finding(Severity.ERROR, area, messageOf(e)));
        }
    }

    private void addresses(List<Finding> findings, List<ServiceInfo> known) {
        Map<String, List<String>> byAddress = new LinkedHashMap<>();
        for (ServiceInfo info : known) {
            if (info.getPort() <= 0) {
                continue;
            }
            byAddress.computeIfAbsent(address(info.getHost(), info.getPort()),
                    ignored -> new ArrayList<>()).add(info.getName());
        }
        List<String> duplicates = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : byAddress.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicates.add(String.join(", ", entry.getValue()) + " → " + entry.getKey());
            }
        }
        if (duplicates.isEmpty()) {
            findings.add(new Finding(Severity.OK, "Adressen", "keine doppelt belegte Service-Adresse"));
        } else {
            findings.add(new Finding(Severity.ERROR, "Adressen",
                    "mehrfach belegt: " + String.join("; ", duplicates)));
        }
        staleServerEntries(findings, known);
    }

    /**
     * Ein Proxy führt in {@code velocity.toml} jeden Service unter seinem Namen.
     * Bleibt dort ein alter Name auf derselben Adresse stehen, wechselt Velocity
     * den Spieler endlos zwischen beiden Einträgen — genau das Fehlerbild, das
     * die Bridge mit dem Adressvergleich abfängt (§14).
     */
    private void staleServerEntries(List<Finding> findings, List<ServiceInfo> known) {
        Map<String, String> registry = new HashMap<>();
        for (ServiceInfo info : known) {
            if (info.getType() == ServiceType.SERVER && info.getPort() > 0) {
                registry.put(address(info.getHost(), info.getPort()), info.getName());
            }
        }
        List<String> stale = new ArrayList<>();
        for (ServiceInfo proxy : known) {
            if (proxy.getType() != ServiceType.PROXY) {
                continue;
            }
            Optional<Path> directory = ServiceDirectories.directory(layout, proxy.getName());
            if (directory.isEmpty()) {
                continue;
            }
            try {
                for (ForwardingCheck.ServerEntry entry : ForwardingCheck.readServers(directory.get())) {
                    String owner = registry.get(normalizeAddress(entry.address()));
                    if (owner != null && !owner.equalsIgnoreCase(entry.name())) {
                        stale.add(proxy.getName() + ": '" + entry.name() + "' zeigt auf "
                                + entry.address() + " (" + owner + ")");
                    }
                }
            } catch (IOException e) {
                findings.add(new Finding(Severity.WARNING, "Adressen",
                        proxy.getName() + ": " + ForwardingCheck.VELOCITY_CONFIG
                                + " nicht lesbar (" + e.getMessage() + ")"));
            }
        }
        if (!stale.isEmpty()) {
            findings.add(new Finding(Severity.WARNING, "Adressen",
                    "veraltete Server-Einträge im Proxy: " + String.join("; ", stale)));
        }
    }

    /**
     * Die Rolle eines Services kommt aus seiner Gruppe: Eine Velocity-Gruppe ist ein
     * Proxy, auch wenn sie als SERVER angelegt wurde. Genau dieser Widerspruch wird
     * gemeldet, statt das Paper-Backend in einem Proxy-Ordner zu suchen.
     */
    private void forwarding(List<Finding> findings, List<ServiceInfo> known,
                            Map<String, ServiceGroup> definitions, String secret) {
        Map<String, Path> proxies = new LinkedHashMap<>();
        Map<String, Path> backends = new LinkedHashMap<>();
        List<String> withoutDirectory = new ArrayList<>();
        boolean hasProxyGroup = false;
        for (ServiceInfo info : known) {
            ServiceGroup group = definitions.get(info.getGroup());
            boolean proxy = isProxyService(info, group);
            // Auch eine falsch typisierte Velocity-Gruppe ist als Proxy gemeint: Der
            // Hinweis auf einen fehlenden Proxy wäre hier nur ein Folgefehler.
            if (proxy || (group != null && Platforms.isProxy(group.getPlatform()))) {
                hasProxyGroup = true;
            }
            if (group != null && Platforms.isProxy(group.getPlatform()) != proxy) {
                boolean platformIsProxy = Platforms.isProxy(group.getPlatform());
                findings.add(new Finding(Severity.ERROR, "Forwarding",
                        "Gruppe '" + group.getName() + "' ist als " + group.getType()
                                + " angelegt, die Plattform '" + group.getPlatform() + "' ist aber "
                                + (platformIsProxy ? "eine Proxy-Plattform" : "eine Server-Plattform")
                                + " — mit --type " + (platformIsProxy ? "PROXY" : "SERVER")
                                + " korrigieren"));
                continue;
            }
            Optional<Path> directory = ServiceDirectories.directory(layout, info.getName());
            if (directory.isEmpty()) {
                if (info.getState() != ServiceState.STOPPED) {
                    withoutDirectory.add(info.getName());
                }
                continue;
            }
            if (proxy) {
                proxies.put(info.getName(), directory.get());
            } else {
                backends.put(info.getName(), directory.get());
            }
        }
        if (!withoutDirectory.isEmpty()) {
            findings.add(new Finding(Severity.WARNING, "Services",
                    "Ordner fehlt: " + String.join(", ", withoutDirectory)));
        }
        if (proxies.isEmpty() && backends.isEmpty()) {
            if (!hasProxyGroup) {
                findings.add(new Finding(Severity.WARNING, "Forwarding",
                        "keine Service-Ordner gefunden — noch nichts gestartet?"));
            }
            return;
        }

        List<ForwardingCheck.Finding> mismatches = ForwardingCheck.compare(proxies, backends, secret);
        if (mismatches.isEmpty()) {
            findings.add(new Finding(Severity.OK, "Forwarding",
                    proxies.size() + " Proxy(s) und " + backends.size()
                            + " Backend(s) nutzen modernes Forwarding mit demselben Secret"));
        } else {
            for (ForwardingCheck.Finding mismatch : mismatches) {
                findings.add(new Finding(
                        mismatch.severity() == ForwardingCheck.Severity.ERROR
                                ? Severity.ERROR : Severity.WARNING,
                        "Forwarding",
                        mismatch.service() + ": " + mismatch.message()
                                + repairHint(mismatch.service(), proxies)));
            }
        }
        if (proxies.isEmpty() && !hasProxyGroup) {
            findings.add(new Finding(Severity.WARNING, "Forwarding",
                    "kein Proxy gefunden — die Backends warten auf einen Proxy"));
        }
    }

    /**
     * Nennt den Befehl, der eine falsche Proxy-Konfiguration geradezieht. Hat der
     * Template-Preparer den Ordner noch nie gesehen (etwa weil eine Jar ihn vorher
     * gefüllt hat), genügt ein Neustart; danach überschreibt {@code reapply} die
     * Dateien aus den Templates, ohne den Service zu starten.
     */
    private static String repairHint(String service, Map<String, Path> proxies) {
        Path directory = proxies.get(service);
        if (directory == null) {
            return "";
        }
        if (!Files.isRegularFile(directory.resolve(TemplatePreparer.PREPARED_MARKER))) {
            return " — 'service restart " + service + "' wendet die Templates an";
        }
        return " — 'service reapply " + service + "' überschreibt die Dateien aus den Templates";
    }

    /**
     * @return Services, deren Forwarding-Dateien nicht zur Konfiguration passen
     */
    private Map<String, ServiceInfo> brokenForwarding(Map<String, ServiceGroup> definitions) {
        Map<String, Path> proxies = new LinkedHashMap<>();
        Map<String, Path> backends = new LinkedHashMap<>();
        Map<String, ServiceInfo> known = new HashMap<>();
        for (ServiceInfo info : services.get()) {
            ServiceGroup group = definitions.get(info.getGroup());
            if (group != null && Platforms.isProxy(group.getPlatform())
                    != (group.getType() == ServiceType.PROXY)) {
                // Erst die Gruppe korrigieren; ein Widerspruch ist keine Dateifrage.
                continue;
            }
            Optional<Path> directory = ServiceDirectories.directory(layout, info.getName());
            if (directory.isEmpty()) {
                continue;
            }
            known.put(info.getName(), info);
            if (isProxyService(info, group)) {
                proxies.put(info.getName(), directory.get());
            } else {
                backends.put(info.getName(), directory.get());
            }
        }
        Map<String, ServiceInfo> broken = new LinkedHashMap<>();
        for (ForwardingCheck.Finding mismatch : ForwardingCheck.compare(proxies, backends,
                config.get().proxy().forwardingSecret())) {
            ServiceInfo info = known.get(mismatch.service());
            if (info != null) {
                broken.put(mismatch.service(), info);
            }
        }
        return broken;
    }

    private static boolean isProxyService(ServiceInfo info, ServiceGroup group) {
        return group == null
                ? info.getType() == ServiceType.PROXY
                : group.getType() == ServiceType.PROXY;
    }

    /**
     * Ohne Proxy-Gruppe kommt kein Spieler herein, ohne Fallback-Ziel reicht der
     * Proxy neue Spieler an niemanden weiter (§9.1, §14). Der Check sieht sich die
     * Gruppen an, nicht die laufenden Services — er greift also auch, bevor der
     * erste Service gestartet ist.
     */
    private void groups(List<Finding> findings, Map<String, ServiceGroup> definitions) {
        List<ServiceGroup> proxies = definitions.values().stream()
                .filter(Doctor::isProxyGroup)
                .toList();
        List<ServiceGroup> backends = definitions.values().stream()
                .filter(group -> !isProxyGroup(group))
                .toList();
        if (proxies.isEmpty() && backends.isEmpty()) {
            findings.add(new Finding(Severity.ERROR, "Gruppen",
                    "keine Gruppen definiert — ohne sie startet die Cloud nichts"
                            + " (group create oder den Erststart-Wizard nutzen)"));
            return;
        }
        if (proxies.isEmpty()) {
            findings.add(new Finding(Severity.ERROR, "Gruppen",
                    "keine Proxy-Gruppe — Spieler erreichen die Cloud nicht"
                            + " (group create Proxy --type PROXY --platform velocity)"));
        }
        if (backends.isEmpty()) {
            findings.add(new Finding(Severity.ERROR, "Gruppen",
                    "keine Server-Gruppe — der Proxy hat kein Ziel für Spieler"
                            + " (group create Lobby --platform paper --fallback)"));
            return;
        }
        List<ServiceGroup> fallbacks = backends.stream()
                .filter(ServiceGroup::getFallback)
                .toList();
        if (fallbacks.isEmpty()) {
            findings.add(new Finding(Severity.WARNING, "Gruppen",
                    "kein Fallback-Ziel — neue Spieler haben keinen Server"
                            + " (group edit " + backends.get(0).getName() + " --fallback)"));
        } else if (!proxies.isEmpty()) {
            findings.add(new Finding(Severity.OK, "Gruppen",
                    "Proxy " + names(proxies) + ", Fallback " + names(fallbacks)));
        }
    }

    /**
     * Eine Velocity-Gruppe ist auch dann als Proxy gemeint, wenn sie als SERVER
     * angelegt wurde — den Widerspruch meldet bereits {@link #forwarding}.
     */
    private static boolean isProxyGroup(ServiceGroup group) {
        return group.getType() == ServiceType.PROXY || Platforms.isProxy(group.getPlatform());
    }

    private static String names(List<ServiceGroup> groups) {
        return String.join(", ", groups.stream().map(group -> "'" + group.getName() + "'").toList());
    }

    private static String address(String host, int port) {
        return normalizeHost(host) + ":" + port;
    }

    private static String normalizeAddress(String address) {
        int colon = address.lastIndexOf(':');
        if (colon <= 0) {
            return address;
        }
        try {
            return address(address.substring(0, colon).trim(),
                    Integer.parseInt(address.substring(colon + 1).trim()));
        } catch (NumberFormatException e) {
            return address;
        }
    }

    private static String normalizeHost(String host) {
        String normalized = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost") || normalized.equals("::1") || normalized.equals("[::1]")) {
            return "127.0.0.1";
        }
        return normalized;
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
