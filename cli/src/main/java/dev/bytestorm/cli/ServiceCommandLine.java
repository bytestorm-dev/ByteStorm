package dev.bytestorm.cli;

import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.controller.ServiceController;
import dev.bytestorm.controller.scaling.HostMetrics;
import dev.bytestorm.node.lifecycle.ServiceLifecycleException;
import dev.bytestorm.proto.GroupState;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Einfache Service-Kommandos der CLI (§17).
 *
 * <p>Der Dispatcher ist bewusst unabhängig von JLine: Er nimmt eine Zeile, führt
 * sie über den {@link ServiceController} aus und liefert den Text zurück. Die
 * interaktive {@link ServiceConsole} verwendet ihn direkt.
 *
 * <p>Seit der Ausgabe-Schicht laufen Listen über {@link Table} und die
 * Momentaufnahme über {@link Dashboard}. Farben und Symbole kommen aus
 * {@link Theme} und erscheinen nur an einem echten Terminal; an einer Pipe bleibt
 * der Text unverändert.
 */
public final class ServiceCommandLine {

    /** Timeout für einen graceful Stop. */
    public static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    /** Hilfe für alle Kommandos, nach Themen gruppiert. */
    public static final String USAGE = """
            SERVICES
              service list                 alle Services
              service info <name>          Details eines Services
              service start <name>         gestoppten Service starten
              service stop <name>          Service graceful stoppen
              service restart <name>       Service stoppen und neu starten
              service kill <name>          Service sofort beenden
              service reapply <name>       Templates erneut anwenden
              service exec <name> <befehl> Befehl an die Service-Konsole senden
              service screen <name>        Live-Logs + Konsole (nur interaktiv, Strg+])
              service online <name>        Bridge-Status ONLINE melden
              service heartbeat <name>     Heartbeat vermerken
              service heartbeat-check      Heartbeats prüfen und Timeouts melden
              service logs <name>          letzte Logzeilen
              service degraded             degradierte Gruppen
              status                       Übersicht: Services, Gruppen, Host-Last
              watch [sekunden]             dieselbe Übersicht, fortlaufend (nur interaktiv)

            GRUPPEN UND TEMPLATES
              group list|info|create|edit|delete|scale  Gruppen verwalten (group help)
              maintenance <name> on|off    Wartungsmodus einer Gruppe schalten
              templates list|create|delete|copy|sync    Templates verwalten (templates help)

            SPIELER UND MODULE
              player list|info|connect|kick Spieler ansehen und umschalten (player help)
              module list|info|settings|set|enable|disable  Module verwalten (module help)

            BETRIEB
              platform list|tasks|info|versions|install|update  Plattformen (platform help)
              updater versions|check|update    Cloud aktualisieren (updater help)
              reload [config|groups]           Konfiguration und Gruppen-Cache neu laden
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
    private final HostMetrics hostMetrics;
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
        this(controller, groups, templates, players, null, null, null, null, null,
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
                null, Instant.now(), Clock.systemUTC());
    }

    /**
     * @param controller  Controller-Fassade
     * @param groups      Gruppen-Kommandos, {@code null} wenn nicht verfügbar
     * @param templates   Template-Kommandos, {@code null} wenn nicht verfügbar
     * @param players     Spieler-Kommandos, {@code null} wenn nicht verfügbar
     * @param reload      {@code reload}, {@code null} wenn nicht verfügbar
     * @param modules     Modul-Kommandos, {@code null} wenn nicht verfügbar
     * @param platforms   Plattform-Kommandos, {@code null} wenn nicht verfügbar
     * @param updater     Updater-Kommandos, {@code null} wenn nicht verfügbar
     * @param hostMetrics Host-Auslastung für {@code status}, {@code null} wenn unbekannt
     */
    public ServiceCommandLine(ServiceController controller, GroupCommandLine groups,
                              TemplateCommandLine templates, PlayerCommandLine players,
                              ReloadCommandLine reload, ModuleCommandLine modules,
                              PlatformCommandLine platforms, UpdaterCommandLine updater,
                              HostMetrics hostMetrics) {
        this(controller, groups, templates, players, reload, modules, platforms, updater,
                hostMetrics, Instant.now(), Clock.systemUTC());
    }

    ServiceCommandLine(ServiceController controller, GroupCommandLine groups, Instant startedAt, Clock clock) {
        this(controller, groups, null, null, null, null, null, null, null, startedAt, clock);
    }

    ServiceCommandLine(ServiceController controller, GroupCommandLine groups,
                       TemplateCommandLine templates, PlayerCommandLine players,
                       Instant startedAt, Clock clock) {
        this(controller, groups, templates, players, null, null, null, null, null, startedAt, clock);
    }

    ServiceCommandLine(ServiceController controller, GroupCommandLine groups,
                       TemplateCommandLine templates, PlayerCommandLine players,
                       ReloadCommandLine reload, ModuleCommandLine modules,
                       PlatformCommandLine platforms, UpdaterCommandLine updater,
                       HostMetrics hostMetrics, Instant startedAt, Clock clock) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.groups = groups;
        this.templates = templates;
        this.players = players;
        this.reload = reload;
        this.modules = modules;
        this.platforms = platforms;
        this.updater = updater;
        this.hostMetrics = hostMetrics;
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
            return Theme.help(USAGE.stripTrailing());
        }
        if (tokens[0].equalsIgnoreCase("info")) {
            return info();
        }
        if (tokens[0].equalsIgnoreCase("status")) {
            return status();
        }
        if (tokens[0].equalsIgnoreCase("group") || tokens[0].equalsIgnoreCase("maintenance")) {
            if (groups == null) {
                return Theme.warn("Gruppen-Kommandos sind nicht verfügbar.");
            }
            // Gruppen-Optionen brauchen alle Tokens; der Service-Pfad teilt nur bis zum Befehl.
            return groups.execute(line.trim().split("\\s+"));
        }
        if (tokens[0].equalsIgnoreCase("templates") || tokens[0].equalsIgnoreCase("template")) {
            if (templates == null) {
                return Theme.warn("Template-Kommandos sind nicht verfügbar.");
            }
            return templates.execute(line.trim().split("\\s+"));
        }
        if (tokens[0].equalsIgnoreCase("player")) {
            if (players == null) {
                return Theme.warn("Spieler-Kommandos sind nicht verfügbar.");
            }
            // `player kick <name> <grund>` braucht den Rest der Zeile als Begründung.
            return players.execute(line.trim());
        }
        if (tokens[0].equalsIgnoreCase("reload")) {
            if (reload == null) {
                return Theme.warn("Reload ist nicht verfügbar.");
            }
            return reload.execute(line.trim().split("\\s+"));
        }
        if (tokens[0].equalsIgnoreCase("module")) {
            if (modules == null) {
                return Theme.warn("Modul-Kommandos sind nicht verfügbar.");
            }
            return modules.execute(line.trim().split("\\s+"));
        }
        if (tokens[0].equalsIgnoreCase("platform")) {
            if (platforms == null) {
                return Theme.warn("Plattform-Kommandos sind nicht verfügbar.");
            }
            return platforms.execute(line.trim().split("\\s+"));
        }
        if (tokens[0].equalsIgnoreCase("updater")) {
            if (updater == null) {
                return Theme.warn("Updater-Kommandos sind nicht verfügbar.");
            }
            return updater.execute(line.trim().split("\\s+"));
        }
        if (tokens[0].equalsIgnoreCase("watch")) {
            return Theme.warn("watch ist nur in der interaktiven Konsole verfügbar"
                    + " (Ctrl+C beendet die Ansicht).");
        }
        if (tokens[0].equalsIgnoreCase("clear") || tokens[0].equalsIgnoreCase("exit")
                || tokens[0].equalsIgnoreCase("quit") || tokens[0].equalsIgnoreCase("shutdown")
                || tokens[0].equalsIgnoreCase("stop")) {
            return tokens[0] + " wird von der interaktiven Konsole ausgeführt";
        }
        if (!tokens[0].equalsIgnoreCase("service")) {
            return Theme.warn("Unbekanntes Kommando: " + tokens[0] + " (help für Hilfe)");
        }
        if (tokens.length < 2) {
            return Theme.help(USAGE.stripTrailing());
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
            return Theme.warn("service screen ist nur in der interaktiven Konsole verfügbar"
                    + " (Ctrl+] beendet)");
        }
        if (name == null) {
            return Theme.warn("Service-Name fehlt (help für Hilfe)");
        }
        Optional<ServiceInfo> service = controller.byName(name);
        if (service.isEmpty()) {
            return Theme.warn("Unbekannter Service: " + name);
        }
        ServiceInfo info = service.get();
        if (info.getId().isEmpty()) {
            return Theme.warn("Service ohne ID: " + name);
        }
        ServiceId id = ServiceId.parse(info.getId());
        try {
            return switch (command) {
                case "info" -> info(info);
                case "start" -> Theme.ok("Service " + name + " gestartet: "
                        + Theme.serviceState(controller.start(id).getState()));
                case "stop" -> Theme.ok("Service " + name + " gestoppt: "
                        + Theme.serviceState(controller.stop(id, STOP_TIMEOUT, false).getState()));
                case "restart" -> Theme.ok("Service " + name + " neu gestartet: "
                        + Theme.serviceState(controller.restart(id, STOP_TIMEOUT).getState()));
                case "kill" -> Theme.ok("Service " + name + " gekillt: "
                        + Theme.serviceState(controller.kill(id).getState()));
                case "reapply" -> Theme.ok("Templates von " + name + " erneut angewendet: "
                        + Theme.serviceState(controller.reapply(id).getState()));
                case "exec" -> exec(name, argument, id);
                case "online" -> Theme.ok("Service " + name + " ist "
                        + Theme.serviceState(controller.online(id).getState()));
                case "heartbeat" -> {
                    controller.heartbeat(id);
                    yield Theme.ok("Heartbeat für " + name + " vermerkt");
                }
                case "logs" -> logs(name, controller.logs(id));
                default -> Theme.warn("Unbekanntes Service-Kommando: " + command + " (help für Hilfe)");
            };
        } catch (ServiceLifecycleException | RuntimeException e) {
            return Theme.fail("Fehler: " + messageOf(e));
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
            String nl = System.lineSeparator();
            return String.join(nl,
                    Theme.bold("ByteStorm Cloud"),
                    Theme.field("Uptime", formatUptime(Duration.between(startedAt, clock.instant()))),
                    Theme.field("Java", Runtime.version().toString()),
                    Theme.field("Services", services.size() + " ("
                            + (online == 0 ? Theme.dim(String.valueOf(online))
                            : Theme.green(String.valueOf(online))) + " online)"),
                    Theme.field("Gruppen", String.valueOf(groups.size())),
                    Theme.field("Degradiert", degraded.isEmpty()
                            ? Theme.dim("keine") : Theme.red(String.join(", ", degraded))),
                    Theme.field("Speicher", usedMb + " MB / " + maxMb + " MB"));
        } catch (RuntimeException e) {
            return Theme.fail("Fehler: " + messageOf(e));
        }
    }

    /**
     * Momentaufnahme für {@code status} und {@code watch}.
     *
     * @return Services, Gruppen, Speicher und Host-Last
     * @throws RuntimeException wenn Controller oder Gruppen nicht lesbar sind
     */
    public Dashboard.Snapshot dashboardSnapshot() {
        List<ServiceInfo> services = controller.services();
        List<String> degraded = List.copyOf(new TreeSet<>(controller.degradedGroups()));

        List<Dashboard.ServiceRow> rows = new ArrayList<>(services.size());
        for (ServiceInfo info : services) {
            rows.add(new Dashboard.ServiceRow(info.getName(), info.getGroup(), info.getState(),
                    info.getPort(), info.getOnlinePlayers(), info.getMaxPlayers(),
                    info.getRuntime().name()));
        }
        rows.sort(Comparator.comparing(Dashboard.ServiceRow::name, String.CASE_INSENSITIVE_ORDER));

        List<ServiceGroup> definitions = groups == null ? List.of() : groups.groupList();
        List<Dashboard.GroupRow> groupRows = new ArrayList<>(definitions.size());
        for (ServiceGroup group : definitions) {
            int running = (int) services.stream()
                    .filter(info -> info.getGroup().equals(group.getName()))
                    .count();
            GroupState state = degraded.contains(group.getName()) ? GroupState.DEGRADED
                    : group.getMaintenance() ? GroupState.MAINTENANCE : GroupState.HEALTHY;
            groupRows.add(new Dashboard.GroupRow(group.getName(), group.getType(), group.getMode(), state,
                    running, group.getMinServices(), group.getMaxServices(), group.getMaintenance()));
        }
        groupRows.sort(Comparator.comparing(Dashboard.GroupRow::name, String.CASE_INSENSITIVE_ORDER));

        long online = services.stream().filter(info -> info.getState() == ServiceState.ONLINE).count();
        Runtime runtime = Runtime.getRuntime();
        return new Dashboard.Snapshot(
                formatUptime(Duration.between(startedAt, clock.instant())),
                (int) online, services.size(), groupRows.size(), degraded,
                (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
                runtime.maxMemory() / (1024 * 1024),
                hostMetrics == null ? null : hostMetrics.cpuLoad(),
                hostMetrics == null ? null : hostMetrics.memoryLoad(),
                List.copyOf(rows), List.copyOf(groupRows));
    }

    private String status() {
        try {
            return Dashboard.render(dashboardSnapshot());
        } catch (RuntimeException e) {
            return Theme.fail("Fehler: " + messageOf(e));
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

    /**
     * @param online Spieler online
     * @param max    Obergrenze, {@code 0} steht für unbegrenzt (etwa bei Proxies)
     * @return {@code online/max} oder nur die Zahl ohne Obergrenze
     */
    static String players(int online, int max) {
        return max <= 0 ? String.valueOf(online) : online + "/" + max;
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
        List<ServiceInfo> services = new ArrayList<>(controller.services());
        if (services.isEmpty()) {
            return Theme.dim("Keine Services registriert.");
        }
        services.sort(Comparator.comparing(ServiceInfo::getName, String.CASE_INSENSITIVE_ORDER));
        Table table = Table.of("Name", "Gruppe", "Status", "Port", "Spieler", "Runtime");
        for (ServiceInfo info : services) {
            table.row(info.getName(), info.getGroup(), Theme.serviceState(info.getState()),
                    String.valueOf(info.getPort()),
                    players(info.getOnlinePlayers(), info.getMaxPlayers()),
                    info.getRuntime().name());
        }
        return table.render();
    }

    private String info(ServiceInfo info) {
        String nl = System.lineSeparator();
        return String.join(nl,
                Theme.field("Name", Theme.bold(info.getName())),
                Theme.field("ID", info.getId()),
                Theme.field("Gruppe", info.getGroup()),
                Theme.field("Status", Theme.serviceState(info.getState())),
                Theme.field("Port", String.valueOf(info.getPort())),
                Theme.field("Spieler", players(info.getOnlinePlayers(), info.getMaxPlayers())),
                Theme.field("Runtime", info.getRuntime().name()));
    }

    private String exec(String name, String command, ServiceId id) throws ServiceLifecycleException {
        if (command == null || command.isBlank()) {
            return Theme.warn("Konsolenbefehl fehlt (service exec <name> <befehl>)");
        }
        String sent = command.trim();
        controller.sendCommand(id, sent);
        return Theme.ok("Befehl an " + name + " gesendet: " + sent);
    }

    private String logs(String name, List<String> lines) {
        return Theme.dim("Logs von " + name + ":") + System.lineSeparator()
                + String.join(System.lineSeparator(), lines);
    }

    private String degraded() {
        if (controller.degradedGroups().isEmpty()) {
            return Theme.ok("Keine degradierten Gruppen.");
        }
        return Theme.red("Degradierte Gruppen: " + String.join(", ", controller.degradedGroups()));
    }

    private String heartbeatCheck() {
        try {
            List<ServiceId> timedOut = controller.checkHeartbeats();
            if (timedOut.isEmpty()) {
                return Theme.ok("Heartbeat-Prüfung: keine Timeouts.");
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
            return Theme.red("Heartbeat-Timeout (" + labels.size() + "): " + String.join(", ", labels));
        } catch (RuntimeException e) {
            return Theme.fail("Fehler: " + messageOf(e));
        }
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
