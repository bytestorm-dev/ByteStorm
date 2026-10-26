package dev.bytestorm.cli;

import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.controller.ServiceController;
import dev.bytestorm.node.lifecycle.ServiceLifecycleException;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * Einfache Service-Kommandos der CLI (§17).
 *
 * <p>Der Dispatcher ist bewusst unabhängig von JLine: Er nimmt eine Zeile, führt
 * sie über den {@link ServiceController} aus und liefert den Text zurück. Die
 * interaktive {@link ServiceConsole} verwendet ihn direkt.
 */
public final class ServiceCommandLine {

    /** Timeout für einen graceful Stop. */
    public static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    /** Hilfe für alle Kommandos. */
    public static final String USAGE = """
            service list                 alle Services
            service info <name>          Details eines Services
            service start <name>         gestoppten Service starten
            service stop <name>          Service graceful stoppen
            service restart <name>       Service stoppen und neu starten
            service kill <name>          Service sofort beenden
            service reapply <name>       Templates erneut anwenden
            service exec <name> <befehl> Befehl an die Service-Konsole senden
            service screen <name>        Live-Logs + Konsole (nur interaktiv, Ctrl+])
            service online <name>        Bridge-Status ONLINE melden
            service heartbeat <name>     Heartbeat vermerken
            service heartbeat-check      Heartbeats prüfen und Timeouts melden
            service logs <name>          letzte Logzeilen
            service degraded             degradierte Gruppen
            group list|info|create|edit|delete|scale  Gruppen verwalten (group help zeigt die Optionen)
            templates list|create|delete|copy|sync    Templates verwalten (templates help)
            player list|info|connect|kick Spieler ansehen und umschalten (player help)
            module list|info|enable|disable  Module ansehen und schalten (module help)
            platform list|tasks|info|versions|install|update  Plattformen (platform help)
            updater versions|check|update    Cloud aktualisieren (updater help)
            reload [config|groups]           Konfiguration und Gruppen-Cache neu laden
            maintenance <name> on|off    Wartungsmodus einer Gruppe schalten
            info                         Agent-Status und Ressourcen
            clear                        Terminal leeren
            shutdown                     Cloud geordnet beenden (Alias stop)
            exit                         Konsole beenden
            help                         diese Hilfe
            """;

    private final ServiceController controller;
    private final GroupCommandLine groups;
    private final TemplateCommandLine templates;
    private final PlayerCommandLine players;
    private final ReloadCommandLine reload;
    private final ModuleCommandLine modules;
    private final PlatformCommandLine platforms;
    private final UpdaterCommandLine updater;
    private final Instant startedAt;
    private final Clock clock;

    /**
     * @param controller Controller-Fassade
     */
    public ServiceCommandLine(ServiceController controller) {
        this(controller, null);
    }

    /**
     * @param controller Controller-Fassade
     * @param groups     Gruppen-Kommandos, {@code null} wenn nicht verfügbar (z. B. in Tests)
     */
    public ServiceCommandLine(ServiceController controller, GroupCommandLine groups) {
        // Die Casts wählen den Konstruktor mit den Kommando-Gruppen statt den mit der Testuhr.
        this(controller, groups, (TemplateCommandLine) null, (PlayerCommandLine) null);
    }

    /**
     * @param controller Controller-Fassade
     * @param groups     Gruppen-Kommandos, {@code null} wenn nicht verfügbar
     * @param templates  Template-Kommandos, {@code null} wenn nicht verfügbar
     * @param players    Spieler-Kommandos, {@code null} wenn nicht verfügbar
     */
    public ServiceCommandLine(ServiceController controller, GroupCommandLine groups,
                              TemplateCommandLine templates, PlayerCommandLine players) {
        this(controller, groups, templates, players, null, null, null, null,
                Instant.now(), Clock.systemUTC());
    }

    /**
     * @param controller Controller-Fassade
     * @param groups     Gruppen-Kommandos, {@code null} wenn nicht verfügbar
     * @param templates  Template-Kommandos, {@code null} wenn nicht verfügbar
     * @param players    Spieler-Kommandos, {@code null} wenn nicht verfügbar
     * @param reload     {@code reload}, {@code null} wenn nicht verfügbar
     * @param modules    Modul-Kommandos, {@code null} wenn nicht verfügbar
     * @param platforms  Plattform-Kommandos, {@code null} wenn nicht verfügbar
     * @param updater    Updater-Kommandos, {@code null} wenn nicht verfügbar
     */
    public ServiceCommandLine(ServiceController controller, GroupCommandLine groups,
                              TemplateCommandLine templates, PlayerCommandLine players,
                              ReloadCommandLine reload, ModuleCommandLine modules,
                              PlatformCommandLine platforms, UpdaterCommandLine updater) {
        this(controller, groups, templates, players, reload, modules, platforms, updater,
                Instant.now(), Clock.systemUTC());
    }

    ServiceCommandLine(ServiceController controller, GroupCommandLine groups, Instant startedAt, Clock clock) {
        this(controller, groups, null, null, null, null, null, null, startedAt, clock);
    }

    ServiceCommandLine(ServiceController controller, GroupCommandLine groups,
                       TemplateCommandLine templates, PlayerCommandLine players,
                       Instant startedAt, Clock clock) {
        this(controller, groups, templates, players, null, null, null, null, startedAt, clock);
    }

    ServiceCommandLine(ServiceController controller, GroupCommandLine groups,
                       TemplateCommandLine templates, PlayerCommandLine players,
                       ReloadCommandLine reload, ModuleCommandLine modules,
                       PlatformCommandLine platforms, UpdaterCommandLine updater,
                       Instant startedAt, Clock clock) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.groups = groups;
        this.templates = templates;
        this.players = players;
        this.reload = reload;
        this.modules = modules;
        this.platforms = platforms;
        this.updater = updater;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Führt eine Kommandozeile aus.
     *
     * @param line Eingabe, z. B. {@code service list}
     * @return Ausgabe für das Terminal
     */
    public String execute(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }
        String[] tokens = line.trim().split("\\s+", 4);
        if (tokens[0].equalsIgnoreCase("help")) {
            return USAGE.stripTrailing();
        }
        if (tokens[0].equalsIgnoreCase("info")) {
            return info();
        }
        if (tokens[0].equalsIgnoreCase("group") || tokens[0].equalsIgnoreCase("maintenance")) {
            if (groups == null) {
                return "Gruppen-Kommandos sind nicht verfügbar.";
            }
            // Gruppen-Optionen brauchen alle Tokens; der Service-Pfad teilt nur bis zum Befehl.
            return groups.execute(line.trim().split("\\s+"));
        }
        if (tokens[0].equalsIgnoreCase("templates") || tokens[0].equalsIgnoreCase("template")) {
            if (templates == null) {
                return "Template-Kommandos sind nicht verfügbar.";
            }
            return templates.execute(line.trim().split("\\s+"));
        }
        if (tokens[0].equalsIgnoreCase("player")) {
            if (players == null) {
                return "Spieler-Kommandos sind nicht verfügbar.";
            }
            // `player kick <name> <grund>` braucht den Rest der Zeile als Begründung.
            return players.execute(line.trim());
        }
        if (tokens[0].equalsIgnoreCase("reload")) {
            if (reload == null) {
                return "Reload ist nicht verfügbar.";
            }
            return reload.execute(line.trim().split("\\s+"));
        }
        if (tokens[0].equalsIgnoreCase("module")) {
            if (modules == null) {
                return "Modul-Kommandos sind nicht verfügbar.";
            }
            return modules.execute(line.trim().split("\\s+"));
        }
        if (tokens[0].equalsIgnoreCase("platform")) {
            if (platforms == null) {
                return "Plattform-Kommandos sind nicht verfügbar.";
            }
            return platforms.execute(line.trim().split("\\s+"));
        }
        if (tokens[0].equalsIgnoreCase("updater")) {
            if (updater == null) {
                return "Updater-Kommandos sind nicht verfügbar.";
            }
            return updater.execute(line.trim().split("\\s+"));
        }
        if (tokens[0].equalsIgnoreCase("clear") || tokens[0].equalsIgnoreCase("exit")
                || tokens[0].equalsIgnoreCase("quit") || tokens[0].equalsIgnoreCase("shutdown")
                || tokens[0].equalsIgnoreCase("stop")) {
            return tokens[0] + " wird von der interaktiven Konsole ausgeführt";
        }
        if (!tokens[0].equalsIgnoreCase("service")) {
            return "Unbekanntes Kommando: " + tokens[0] + " (help für Hilfe)";
        }
        if (tokens.length < 2) {
            return USAGE.stripTrailing();
        }
        return service(tokens[1].toLowerCase(Locale.ROOT),
                tokens.length > 2 ? tokens[2] : null,
                tokens.length > 3 ? tokens[3] : null);
    }

    private String service(String command, String name, String argument) {
        if (command.equals("list")) {
            return list();
        }
        if (command.equals("degraded")) {
            return degraded();
        }
        if (command.equals("heartbeat-check")) {
            return heartbeatCheck();
        }
        if (command.equals("screen")) {
            return "service screen ist nur in der interaktiven Konsole verfügbar (Ctrl+] beendet)";
        }
        if (name == null) {
            return "Service-Name fehlt (help für Hilfe)";
        }
        Optional<ServiceInfo> service = controller.byName(name);
        if (service.isEmpty()) {
            return "Unbekannter Service: " + name;
        }
        ServiceInfo info = service.get();
        if (info.getId().isEmpty()) {
            return "Service ohne ID: " + name;
        }
        ServiceId id = ServiceId.parse(info.getId());
        try {
            return switch (command) {
                case "info" -> info(info);
                case "start" -> "Service " + name + " gestartet: " + controller.start(id).getState();
                case "stop" -> "Service " + name + " gestoppt: "
                        + controller.stop(id, STOP_TIMEOUT, false).getState();
                case "restart" -> "Service " + name + " neu gestartet: "
                        + controller.restart(id, STOP_TIMEOUT).getState();
                case "kill" -> "Service " + name + " gekillt: " + controller.kill(id).getState();
                case "reapply" -> "Templates von " + name + " erneut angewendet: "
                        + controller.reapply(id).getState();
                case "exec" -> exec(name, argument, id);
                case "online" -> "Service " + name + " ist " + controller.online(id).getState();
                case "heartbeat" -> {
                    controller.heartbeat(id);
                    yield "Heartbeat für " + name + " vermerkt";
                }
                case "logs" -> logs(name, controller.logs(id));
                default -> "Unbekanntes Service-Kommando: " + command + " (help für Hilfe)";
            };
        } catch (ServiceLifecycleException | RuntimeException e) {
            return "Fehler: " + e.getMessage();
        }
    }

    private String info() {
        try {
            List<ServiceInfo> services = controller.services();
            long online = services.stream()
                    .filter(info -> info.getState() == ServiceState.ONLINE)
                    .count();
            Set<String> groups = new TreeSet<>();
            for (ServiceInfo info : services) {
                if (!info.getGroup().isBlank()) {
                    groups.add(info.getGroup());
                }
            }
            Set<String> degraded = new TreeSet<>(controller.degradedGroups());
            Runtime runtime = Runtime.getRuntime();
            long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            long maxMb = runtime.maxMemory() / (1024 * 1024);
            return """
                    ByteStorm Cloud
                    Uptime: %s
                    Java: %s
                    Services: %d (%d online)
                    Gruppen: %d
                    Degradiert: %s
                    Speicher: %d MB / %d MB
                    """.formatted(formatUptime(Duration.between(startedAt, clock.instant())),
                    Runtime.version(), services.size(), online, groups.size(),
                    degraded.isEmpty() ? "keine" : String.join(", ", degraded), usedMb, maxMb)
                    .stripTrailing();
        } catch (RuntimeException e) {
            return "Fehler: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * @return die Gruppennamen für die Tab-Vervollständigung, leer ohne Gruppen-Kommandos
     */
    public List<String> groupNames() {
        return groups == null ? List.of() : groups.names();
    }

    /**
     * @return die Templatenamen für die Tab-Vervollständigung, leer ohne Template-Kommandos
     */
    public List<String> templateNames() {
        return templates == null ? List.of() : templates.names();
    }

    /**
     * @return die Namen der online Spieler für die Tab-Vervollständigung
     */
    public List<String> playerNames() {
        return players == null ? List.of() : players.names();
    }

    static String formatUptime(Duration uptime) {
        long total = Math.max(0, uptime.getSeconds());
        long days = total / 86_400;
        long hours = (total % 86_400) / 3_600;
        long minutes = (total % 3_600) / 60;
        long seconds = total % 60;
        if (days > 0) {
            return days + "d " + String.format("%02dh", hours);
        }
        if (hours > 0) {
            return hours + "h " + String.format("%02dm", minutes);
        }
        if (minutes > 0) {
            return minutes + "m " + String.format("%02ds", seconds);
        }
        return seconds + "s";
    }

    private String list() {
        List<ServiceInfo> services = controller.services();
        if (services.isEmpty()) {
            return "Keine Services registriert.";
        }
        StringJoiner out = new StringJoiner(System.lineSeparator());
        out.add("Name\tGruppe\tStatus\tPort");
        for (ServiceInfo info : services) {
            out.add(info.getName() + "\t" + info.getGroup() + "\t" + info.getState() + "\t" + info.getPort());
        }
        return out.toString();
    }

    private String info(ServiceInfo info) {
        return """
                Name: %s
                ID: %s
                Gruppe: %s
                Status: %s
                Port: %d
                Runtime: %s
                """.formatted(info.getName(), info.getId(), info.getGroup(), info.getState(),
                info.getPort(), info.getRuntime()).stripTrailing();
    }

    private String exec(String name, String command, ServiceId id) throws ServiceLifecycleException {
        if (command == null || command.isBlank()) {
            return "Konsolenbefehl fehlt (service exec <name> <befehl>)";
        }
        String sent = command.trim();
        controller.sendCommand(id, sent);
        return "Befehl an " + name + " gesendet: " + sent;
    }

    private String logs(String name, List<String> lines) {
        return "Logs von " + name + ":" + System.lineSeparator() + String.join(System.lineSeparator(), lines);
    }

    private String degraded() {
        if (controller.degradedGroups().isEmpty()) {
            return "Keine degradierten Gruppen.";
        }
        return "Degradierte Gruppen: " + String.join(", ", controller.degradedGroups());
    }

    private String heartbeatCheck() {
        try {
            List<ServiceId> timedOut = controller.checkHeartbeats();
            if (timedOut.isEmpty()) {
                return "Heartbeat-Prüfung: keine Timeouts.";
            }
            Map<String, String> names = new HashMap<>();
            for (ServiceInfo info : controller.services()) {
                names.put(info.getId(), info.getName());
            }
            List<String> labels = new ArrayList<>(timedOut.size());
            for (ServiceId id : timedOut) {
                labels.add(names.getOrDefault(id.toString(), id.toString()));
            }
            labels.sort(String.CASE_INSENSITIVE_ORDER);
            return "Heartbeat-Timeout (" + labels.size() + "): " + String.join(", ", labels);
        } catch (RuntimeException e) {
            return "Fehler: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

}
