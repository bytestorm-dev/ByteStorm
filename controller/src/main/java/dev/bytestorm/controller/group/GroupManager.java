package dev.bytestorm.controller.group;

import dev.bytestorm.api.service.Platforms;
import dev.bytestorm.controller.scaling.CronSchedule;
import dev.bytestorm.core.config.PortSettings;
import dev.bytestorm.core.mongo.repository.GroupStore;
import dev.bytestorm.core.redis.GroupCache;
import dev.bytestorm.node.template.TemplateLayers;
import dev.bytestorm.proto.LoadRule;
import dev.bytestorm.proto.PlayerCountRule;
import dev.bytestorm.proto.ScheduleRule;
import dev.bytestorm.proto.ScalingRule;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceType;
import lombok.extern.slf4j.Slf4j;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Verwaltet die Gruppen-Definitionen (§9.1).
 *
 * <p>Quelle der Wahrheit ist MongoDB; der {@link GroupCache} beschleunigt
 * wiederkehrende Zugriffe und wird bei jeder Änderung verworfen. Alle
 * Schreibzugriffe laufen über diesen Manager, damit Validierung und
 * Cache-Invalidierung nicht umgangen werden können.
 *
 * <p>Geprüft wird vor dem Speichern: Name eindeutig und verwendbar, Minimum
 * nicht über Maximum, Templates vorhanden, Portbereich ausreichend und die
 * Skalierungsregeln auswertbar. Die Prüfung ist auch einzeln aufrufbar
 * ({@link #validate(ServiceGroup, boolean)}), damit CLI und REST dieselben
 * Meldungen zeigen können, ohne etwas zu speichern.
 *
 * <p>Ein Wechsel des Wartungsmodus ({@link #setMaintenance(String, boolean)})
 * prüft nur die Existenz: Eine bereits gespeicherte Gruppe soll sich auch dann
 * in den Wartungsmodus schalten lassen, wenn sie die aktuellen Regeln nicht mehr
 * erfüllt.
 */
@Slf4j
public final class GroupManager {

    /** Zulässige Gruppennamen; sie werden Teil von Service-Namen und Ordnern. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    /** Feste Template-Ebenen; sie legt das Layout an und existieren immer (§10). */
    private static final Set<String> FIXED_LAYERS = Set.of(
            TemplateLayers.ALL, TemplateLayers.PROXY, TemplateLayers.SERVER, TemplateLayers.FALLBACK);

    /** Höchster Port; Portbereiche enden hier. */
    private static final int MAX_PORT = 65535;

    private final GroupStore store;
    private final GroupCache cache;
    private final PortSettings ports;
    private final Predicate<String> templateExists;

    /**
     * @param store          Persistenz der Gruppen
     * @param cache          Cache der Gruppen
     * @param ports          konfigurierte Port-Pools
     * @param templateExists prüft, ob ein Template unter {@code templates/} liegt
     */
    public GroupManager(GroupStore store,
                        GroupCache cache,
                        PortSettings ports,
                        Predicate<String> templateExists) {
        this.store = Objects.requireNonNull(store, "store");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.ports = Objects.requireNonNull(ports, "ports");
        this.templateExists = Objects.requireNonNull(templateExists, "templateExists");
    }

    /**
     * @return alle Gruppen, alphabetisch nach Name
     */
    public List<ServiceGroup> all() {
        return store.findAll().stream()
                .sorted(Comparator.comparing(ServiceGroup::getName))
                .toList();
    }

    /**
     * @param name Gruppenname
     * @return die Gruppe, falls vorhanden
     */
    public Optional<ServiceGroup> get(String name) {
        Objects.requireNonNull(name, "name");
        Optional<ServiceGroup> cached = cache.get(name);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<ServiceGroup> stored = store.find(name);
        stored.ifPresent(cache::put);
        return stored;
    }

    /**
     * Verwirft den Cache und füllt ihn aus dem Storage neu (§3.1: {@code reload}).
     *
     * <p>Damit wirkt eine Änderung, die an diesem Manager vorbei in der Datenbank
     * passiert ist, sofort — auch Einträge gelöschter Gruppen verschwinden, weil der
     * Cache vorher geleert wird.
     *
     * @return Anzahl der Gruppen, die jetzt gecacht sind
     */
    public int reloadCache() {
        cache.clear();
        List<ServiceGroup> stored = store.findAll();
        for (ServiceGroup group : stored) {
            cache.put(group);
        }
        log.info("Gruppen-Cache neu geladen ({} Gruppen)", stored.size());
        return stored.size();
    }

    /**
     * Legt eine neue Gruppe an.
     *
     * @param group Definition
     * @return die gespeicherte Gruppe
     * @throws GroupValidationException wenn die Definition ungültig ist oder der Name schon belegt ist
     */
    public ServiceGroup create(ServiceGroup group) throws GroupValidationException {
        require(group, true);
        store.upsert(group);
        cache.invalidate(group.getName());
        log.info("Gruppe '{}' angelegt ({} {}, min {} / max {})", group.getName(), group.getPlatform(),
                group.getVersion().isBlank() ? "<neueste>" : group.getVersion(),
                group.getMinServices(), group.getMaxServices());
        return group;
    }

    /**
     * Ersetzt eine bestehende Gruppe vollständig.
     *
     * @param group Definition
     * @return die gespeicherte Gruppe
     * @throws GroupValidationException wenn die Definition ungültig ist oder die Gruppe fehlt
     */
    public ServiceGroup update(ServiceGroup group) throws GroupValidationException {
        require(group, false);
        store.upsert(group);
        cache.invalidate(group.getName());
        log.info("Gruppe '{}' aktualisiert (min {} / max {})", group.getName(),
                group.getMinServices(), group.getMaxServices());
        return group;
    }

    /**
     * @param name Gruppenname
     * @return {@code true}, wenn die Gruppe gelöscht wurde
     */
    public boolean delete(String name) {
        Objects.requireNonNull(name, "name");
        if (!store.delete(name)) {
            return false;
        }
        cache.invalidate(name);
        log.info("Gruppe '{}' gelöscht", name);
        return true;
    }

    /**
     * Schaltet den Wartungsmodus einer Gruppe um (§9.1, §11). Im Wartungsmodus
     * startet der ScalingManager keine neuen Services; bestehende laufen weiter.
     *
     * @param name        Gruppenname
     * @param maintenance neuer Zustand
     * @return die Gruppe mit dem neuen Zustand
     * @throws GroupValidationException wenn die Gruppe nicht existiert
     */
    public ServiceGroup setMaintenance(String name, boolean maintenance) throws GroupValidationException {
        Objects.requireNonNull(name, "name");
        ServiceGroup group = get(name).orElseThrow(() -> missing(name));
        if (!store.setMaintenance(name, maintenance)) {
            throw missing(name);
        }
        cache.invalidate(name);
        log.info("Gruppe '{}': Wartungsmodus {}", name, maintenance ? "aktiv" : "aus");
        return group.toBuilder().setMaintenance(maintenance).build();
    }

    /**
     * Prüft eine Definition, ohne sie zu speichern.
     *
     * @param group    Definition
     * @param creating {@code true} für eine neue Gruppe, {@code false} für ein Update
     * @return alle gefundenen Probleme; eine leere Liste heißt gültig
     */
    public List<String> validate(ServiceGroup group, boolean creating) {
        Objects.requireNonNull(group, "group");
        List<String> problems = new ArrayList<>();

        String name = group.getName() == null ? "" : group.getName().trim();
        if (!NAME.matcher(name).matches()) {
            problems.add("name muss 1 bis 32 Zeichen aus Buchstaben, Ziffern, '-' und '_' enthalten: '"
                    + name + "'");
        } else if (creating && store.find(name).isPresent()) {
            problems.add("Gruppe '" + name + "' existiert bereits");
        } else if (!creating && store.find(name).isEmpty()) {
            problems.add("Gruppe '" + name + "' existiert nicht");
        }

        String platform = group.getPlatform() == null ? "" : group.getPlatform().trim();
        if (platform.isEmpty()) {
            problems.add("platform ist Pflicht (z. B. paper, velocity)");
        } else if (platform.contains("/") || platform.contains("\\")) {
            problems.add("platform darf keine Pfadtrenner enthalten: '" + platform + "'");
        } else if (Platforms.isProxy(platform) && group.getType() != ServiceType.PROXY) {
            // Sonst startet ein Velocity-Jar als Server-Gruppe: falsche Templates, falscher
            // Port-Pool und kein Proxy für die Backends.
            problems.add("platform '" + platform + "' ist eine Proxy-Plattform — type PROXY setzen");
        } else if (!Platforms.isProxy(platform) && group.getType() == ServiceType.PROXY) {
            problems.add("type PROXY braucht eine Proxy-Plattform (z. B. velocity), nicht '" + platform + "'");
        }

        if (group.getMaxServices() > 0 && group.getMinServices() > group.getMaxServices()) {
            problems.add("minServices (" + group.getMinServices() + ") darf maxServices ("
                    + group.getMaxServices() + ") nicht überschreiten");
        }
        if (group.getType() != ServiceType.PROXY && group.getMaxPlayers() == 0) {
            problems.add("maxPlayers ist für Server-Gruppen Pflicht (Grundlage der Auslastung)");
        }
        if (group.hasJavaVersion() && group.getJavaVersion() < 1) {
            problems.add("javaVersion muss ein gültiges JDK-Major sein: " + group.getJavaVersion());
        }

        problems.addAll(validatePorts(group));
        problems.addAll(validateTemplates(group));
        problems.addAll(validateRules(group));
        return problems;
    }

    private List<String> validatePorts(ServiceGroup group) {
        List<String> problems = new ArrayList<>();
        boolean proxy = group.getType() == ServiceType.PROXY;
        if (proxy && group.getMaxServices() != 1) {
            problems.add("Proxy-Gruppen belegen ports.proxy (" + ports.proxy()
                    + "); maxServices muss 1 sein");
        }
        int available = proxy ? 1 : MAX_PORT - ports.serverStart() + 1;
        int required = group.getMaxServices() > 0 ? group.getMaxServices() : group.getMinServices();
        if (required > available) {
            problems.add("Portbereich passt nicht: " + required + " Services brauchen " + required
                    + " Ports, verfügbar sind " + available
                    + (proxy ? " (ports.proxy)" : " (ports.serverStart " + ports.serverStart() + ")"));
        }
        return problems;
    }

    private List<String> validateTemplates(ServiceGroup group) {
        List<String> problems = new ArrayList<>();
        for (String template : group.getTemplatesList()) {
            String trimmed = template.trim();
            if (trimmed.isEmpty() || trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
                problems.add("Template-Name ist ungültig: '" + template + "'");
                continue;
            }
            if (!FIXED_LAYERS.contains(trimmed) && !templateExists.test(trimmed)) {
                problems.add("Template '" + trimmed + "' existiert nicht in templates/");
            }
        }
        return problems;
    }

    private List<String> validateRules(ServiceGroup group) {
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (ScalingRule rule : group.getScalingRulesList()) {
            if (!rule.getEnabled()) {
                // Deaktivierte Regeln greifen nicht (§11) und dürfen das Speichern nicht blockieren.
                continue;
            }
            if (rule.getId().isBlank()) {
                problems.add("Jede aktive Skalierungsregel braucht eine ID");
                continue;
            }
            if (!ids.add(rule.getId())) {
                problems.add("Skalierungsregel '" + rule.getId() + "' ist doppelt");
                continue;
            }
            switch (rule.getRuleCase()) {
                case PLAYER_COUNT -> problems.addAll(validatePlayerCount(rule.getId(), rule.getPlayerCount()));
                case SCHEDULE -> problems.addAll(validateSchedule(rule.getId(), rule.getSchedule()));
                case LOAD -> problems.addAll(validateLoad(rule.getId(), rule.getLoad()));
                default -> problems.add("Skalierungsregel '" + rule.getId() + "' hat keinen Typ");
            }
        }
        return problems;
    }

    private static List<String> validatePlayerCount(String id, PlayerCountRule rule) {
        List<String> problems = new ArrayList<>();
        double scaleUp = rule.getScaleUpUtilization();
        double idle = rule.getIdleUtilization();
        if (scaleUp <= 0 || scaleUp > 1) {
            problems.add("Regel '" + id + "': scaleUpUtilization muss zwischen 0 und 1 liegen: " + scaleUp);
        }
        if (idle < 0 || idle > 1) {
            problems.add("Regel '" + id + "': idleUtilization muss zwischen 0 und 1 liegen: " + idle);
        }
        if (scaleUp > 0 && idle > scaleUp) {
            problems.add("Regel '" + id + "': idleUtilization (" + idle
                    + ") muss unter scaleUpUtilization (" + scaleUp + ") liegen");
        }
        return problems;
    }

    private static List<String> validateSchedule(String id, ScheduleRule rule) {
        List<String> problems = new ArrayList<>();
        try {
            CronSchedule.parse(rule.getCron());
        } catch (RuntimeException e) {
            problems.add("Regel '" + id + "': ungültiger Cron-Ausdruck: " + e.getMessage());
        }
        if (!rule.getTimezone().isBlank()) {
            try {
                ZoneId.of(rule.getTimezone());
            } catch (RuntimeException e) {
                problems.add("Regel '" + id + "': unbekannte Zeitzone: '" + rule.getTimezone() + "'");
            }
        }
        if (rule.getMaxServices() > 0 && rule.getMinServices() > rule.getMaxServices()) {
            problems.add("Regel '" + id + "': minServices (" + rule.getMinServices()
                    + ") darf maxServices (" + rule.getMaxServices() + ") nicht überschreiten");
        }
        return problems;
    }

    private static List<String> validateLoad(String id, LoadRule rule) {
        List<String> problems = new ArrayList<>();
        if (rule.getMaxHostCpu() < 0 || rule.getMaxHostCpu() > 100) {
            problems.add("Regel '" + id + "': maxHostCpu muss zwischen 0 und 100 liegen: "
                    + rule.getMaxHostCpu());
        }
        if (rule.getMaxHostMemory() < 0 || rule.getMaxHostMemory() > 100) {
            problems.add("Regel '" + id + "': maxHostMemory muss zwischen 0 und 100 liegen: "
                    + rule.getMaxHostMemory());
        }
        return problems;
    }

    private void require(ServiceGroup group, boolean creating) throws GroupValidationException {
        Objects.requireNonNull(group, "group");
        List<String> problems = validate(group, creating);
        if (!problems.isEmpty()) {
            throw new GroupValidationException(group.getName(), problems);
        }
    }

    private static GroupValidationException missing(String name) {
        return new GroupValidationException(name, List.of("Gruppe '" + name + "' existiert nicht"));
    }
}
