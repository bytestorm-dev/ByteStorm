package dev.bytestorm.plugins.proxy.command;

import dev.bytestorm.plugins.proxy.cloud.CloudClient;
import dev.bytestorm.plugins.proxy.cloud.CloudClient.GroupView;
import dev.bytestorm.plugins.proxy.cloud.CloudClient.PlayerView;
import dev.bytestorm.plugins.proxy.cloud.CloudClient.ServiceView;
import dev.bytestorm.plugins.proxy.cloud.CloudException;
import dev.bytestorm.plugins.proxy.message.Messages;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /cloud} — die Cloud-Kommandos als Proxy-Befehl (§16, §17).
 *
 * <p>Der Dispatcher ist frei von Velocity: Er nimmt eine Zeile und liefert
 * {@link Reply Antwortzeilen}, übersetzt über {@link Messages}. Dadurch sind
 * alle Kommandos ohne laufenden Proxy testbar, und jeder Satz bleibt in den
 * Sprachdateien — auch die Fehlermeldungen und die Hilfe.
 *
 * <p>Der Umfang entspricht der CLI ohne deren interaktive Teile:
 * {@code watch} und {@code screen} brauchen ein Terminal und werden deshalb
 * abgelehnt. Cloud-Kommandos gehen über das REST-Modul (§11); {@code tablist}
 * und {@code language} betreffen nur dieses Plugin und arbeiten auch ohne
 * Cloud-Anbindung. {@code service restart-crashed} ist die zweite Ausnahme: Es
 * startet alle abgestürzten Services neu, damit ein Klick statt einer Zeile je
 * Service genügt.
 *
 * <p>Jedes Kommando prüft eine eigene Node unter {@code bytestorm.command}
 * (siehe {@link CloudPermissions}); {@code status} und {@code player kick}
 * sind damit getrennt vergebbar.
 */
public final class CloudCommand {

    /** Name des Kommandos, ohne Slash. */
    public static final String NAME = "cloud";

    /** Aliasse, unter denen derselbe Befehl erreichbar ist. */
    public static final List<String> ALIASES = List.of("c", "bs", "bytestorm");

    /** Erste Wörter für die Tab-Vervollständigung. */
    private static final List<String> SUGGESTIONS = List.of("status", "service", "group",
            "maintenance", "player", "tablist", "language", "help");

    private static final String SERVICE_USAGE =
            "/cloud service <list|info|logs|start|stop|restart|restart-crashed|kill|reapply|online|exec>";

    /** Unterbefehle von {@code /cloud service}; jeder hat seine eigene Node. */
    private static final List<String> SERVICE_ACTIONS = List.of("list", "info", "logs", "start",
            "stop", "restart", "restart-crashed", "kill", "reapply", "online", "exec");

    /** Ohne Anzahl liefert {@code service logs} so viele Zeilen. */
    static final int DEFAULT_LOG_LINES = 20;

    /** Mehr Zeilen gibt ein einzelner Aufruf nicht heraus, damit der Chat lesbar bleibt. */
    static final int MAX_LOG_LINES = 50;

    /** Zeitpunkt eines Crashs in der Ortszeit der Cloud. */
    private static final DateTimeFormatter CRASH_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final CloudClient cloud;
    private final boolean enabled;
    private final PluginControl control;

    /**
     * @param cloud   Cloud-Anbindung
     * @param enabled {@code true}, wenn {@code cloud.enabled} gesetzt und ein Key vorhanden ist
     * @param control Zugriff auf Tablist und Sprache des Plugins
     */
    public CloudCommand(CloudClient cloud, boolean enabled, PluginControl control) {
        this.cloud = Objects.requireNonNull(cloud, "cloud");
        this.control = Objects.requireNonNull(control, "control");
        this.enabled = enabled;
    }

    /**
     * @return erste Wörter für die Vervollständigung, unabhängig von der Cloud
     */
    public static List<String> suggestions() {
        return SUGGESTIONS;
    }

    /**
     * Führt eine Zeile aus.
     *
     * <p>Jedes Kommando prüft seine eigene Node (siehe {@link CloudPermissions}):
     * {@code status} braucht {@code bytestorm.command.status}, {@code player kick}
     * die Node {@code bytestorm.command.player.kick}. Wildcards fassen Ränge
     * zusammen, ohne dass ein Spieler-Mod Services stoppen darf.
     *
     * @param line        Eingabe ohne {@code /cloud}, z. B. {@code service list}
     * @param permissions Berechtigungen des Absenders
     * @param messages    Texte der aktuellen Sprache
     * @return Antwortzeilen, bereits übersetzt
     */
    public List<Reply> execute(String line, CloudPermissions permissions, Messages messages) {
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(messages, "messages");
        String input = line == null ? "" : line.trim();
        if (input.isEmpty() || input.equalsIgnoreCase("help")) {
            return lines(messages.text("cloud.usage", Map.of()));
        }
        String[] tokens = input.split("\\s+");
        String head = tokens[0].toLowerCase(Locale.ROOT);
        if (!head.equals("tablist") && !head.equals("language") && !enabled) {
            return List.of(Reply.of(messages.text("cloud.disabled", Map.of())));
        }
        try {
            return switch (head) {
                case "status" -> guard("status", permissions, messages, () -> status(messages));
                case "service" -> service(tokens, permissions, messages);
                case "group" -> group(tokens, permissions, messages);
                case "maintenance" -> maintenance(tokens, permissions, messages);
                case "player" -> player(tokens, permissions, messages);
                case "tablist" -> guard("tablist", permissions, messages,
                        () -> tablist(tokens, messages));
                case "language" -> guard("language", permissions, messages,
                        () -> language(messages));
                case "watch", "screen" -> List.of(Reply.of(messages.text("cloud.onlyCli",
                        Map.of("command", head))));
                default -> List.of(Reply.of(messages.text("cloud.unknown",
                        Map.of("command", head))));
            };
        } catch (CloudException e) {
            return List.of(Reply.of(messages.text("cloud.error", Map.of("reason", reasonOf(e)))));
        }
    }

    /**
     * Führt ein Kommando nur aus, wenn eine seiner Nodes vergeben ist.
     *
     * @param path        Kommandopfad, z. B. {@code service.restart}
     * @param permissions Berechtigungen des Absenders
     * @param messages    Texte
     * @param body        das Kommando
     * @return Antwortzeilen oder die Ablehnung samt fehlender Node
     */
    private static List<Reply> guard(String path, CloudPermissions permissions, Messages messages,
                                     Supplier<List<Reply>> body) {
        if (!permissions.can(path)) {
            return List.of(denied(path, messages));
        }
        return body.get();
    }

    /**
     * @param path     Kommandopfad
     * @param messages Texte
     * @return die Ablehnung samt der Node, die ein Admin vergeben müsste
     */
    private static Reply denied(String path, Messages messages) {
        return Reply.of(messages.text("cloud.denied", Map.of(
                "command", "/" + NAME + " " + path.replace('.', ' '),
                "permission", CloudPermissions.node(path))));
    }

    /**
     * {@code /cloud tablist [on|off]} — Zustand anzeigen oder umschalten. Der
     * Schalter landet in {@code config.json}; der nächste Tick übernimmt ihn.
     *
     * @param tokens   Eingabe
     * @param messages Texte
     * @return Bestätigung oder der aktuelle Zustand
     */
    private List<Reply> tablist(String[] tokens, Messages messages) {
        if (tokens.length < 2) {
            return List.of(Reply.of(messages.text("cloud.tablist.state",
                    Map.of("state", switchText(control.tablistEnabled(), messages)))));
        }
        Boolean wanted = switch (tokens[1].toLowerCase(Locale.ROOT)) {
            case "on", "true", "an" -> Boolean.TRUE;
            case "off", "false", "aus" -> Boolean.FALSE;
            default -> null;
        };
        if (wanted == null) {
            return List.of(Reply.of(messages.text("cloud.usageHint",
                    Map.of("usage", "/cloud tablist on|off"))));
        }
        try {
            control.tablistEnabled(wanted);
        } catch (RuntimeException e) {
            return List.of(Reply.of(messages.text("cloud.tablist.failed",
                    Map.of("reason", reasonOf(e)))));
        }
        return List.of(Reply.of(messages.text("cloud.tablist.set",
                Map.of("state", switchText(wanted, messages)))));
    }

    /**
     * {@code /cloud language} — eingestellte und tatsächlich verwendete Sprache.
     *
     * @param messages Texte
     * @return eine Zeile, ggf. mit Hinweis auf die fehlende Sprachdatei
     */
    private List<Reply> language(Messages messages) {
        String configured = control.language();
        String used = control.effectiveLanguage();
        if (configured == null || configured.isBlank()) {
            configured = used;
        }
        if (used == null || used.isBlank()) {
            used = configured;
        }
        if (used == null) {
            return List.of(Reply.of(messages.text("cloud.notRunning", Map.of())));
        }
        if (configured == null || configured.equalsIgnoreCase(used)) {
            return List.of(Reply.of(messages.text("cloud.language", Map.of("language", used))));
        }
        return List.of(Reply.of(messages.text("cloud.language.fallback",
                Map.of("language", used, "configured", configured))));
    }

    /**
     * {@code status}: Services, Gruppen und die letzten Abstürze samt Neustart.
     *
     * @param messages Texte
     * @return Kopfzeile, je Service eine Zeile und — falls vorhanden — die Abstürze
     */
    private List<Reply> status(Messages messages) {
        List<ServiceView> services = cloud.services();
        List<GroupView> groups = cloud.groups();
        long online = services.stream().filter(service -> service.state().equals("ONLINE")).count();
        long maintenance = groups.stream().filter(GroupView::maintenance).count();

        String header = messages.text("cloud.status.header", Map.of(
                "online", String.valueOf(online),
                "total", String.valueOf(services.size()),
                "groups", String.valueOf(groups.size()),
                "maintenance", String.valueOf(maintenance)));
        List<Reply> out = new ArrayList<>();
        out.add(Reply.of(header));
        if (services.isEmpty()) {
            out.add(Reply.of(messages.text("cloud.status.empty", Map.of())));
            return out;
        }
        for (ServiceView service : services) {
            out.add(Reply.of(row(service, messages)));
        }
        List<ServiceView> crashed = services.stream()
                .filter(service -> service.crash().crashed())
                .toList();
        if (!crashed.isEmpty()) {
            out.add(Reply.of(messages.text("cloud.status.crashHeader", Map.of())));
            for (ServiceView service : crashed) {
                out.add(Reply.of(messages.text("cloud.status.crashRow", Map.of(
                        "service", service.name(),
                        "group", service.group(),
                        "time", time(service.crash().time()),
                        "code", String.valueOf(service.crash().exitCode()),
                        "reason", service.crash().reason()))));
            }
            // Ein Klick startet alle abgestürzten Services neu; sonst wüchse die
            // Ausgabe mit jedem Absturz um eine Zeile.
            out.add(Reply.run(
                    messages.text("cloud.status.restartAll",
                            Map.of("count", String.valueOf(crashed.size()))),
                    "/" + NAME + " service restart-crashed"));
        }
        return out;
    }

    private List<Reply> service(String[] tokens, CloudPermissions permissions, Messages messages) {
        if (tokens.length < 2) {
            return List.of(Reply.of(messages.text("cloud.usageHint",
                    Map.of("usage", SERVICE_USAGE))));
        }
        String action = tokens[1].toLowerCase(Locale.ROOT);
        if (!SERVICE_ACTIONS.contains(action)) {
            return List.of(Reply.of(messages.text("cloud.unknown",
                    Map.of("command", "service " + tokens[1]))));
        }
        if (action.equals("list")) {
            return guard("service.list", permissions, messages, () -> serviceList(messages));
        }
        if (action.equals("restart-crashed")) {
            return guard("service.restart-crashed", permissions, messages,
                    () -> restartCrashed(messages));
        }
        if (tokens.length < 3) {
            return List.of(Reply.of(messages.text("cloud.usageHint",
                    Map.of("usage", SERVICE_USAGE))));
        }
        if (!permissions.can("service." + action)) {
            return List.of(denied("service." + action, messages));
        }
        if (action.equals("start")) {
            ServiceView started = cloud.start(tokens[2]);
            return List.of(Reply.of(messages.text("cloud.service.started", Map.of(
                    "name", started.name(),
                    "group", started.group().isBlank() ? tokens[2] : started.group(),
                    "state", started.state()))));
        }

        Optional<ServiceView> found = findService(tokens[2]);
        if (found.isEmpty()) {
            return List.of(Reply.of(messages.text("cloud.unknownService",
                    Map.of("name", tokens[2]))));
        }
        ServiceView service = found.get();
        return switch (action) {
            case "info" -> info(service, messages);
            case "logs" -> logs(service, tokens.length > 3 ? number(tokens[3]) : null, messages);
            case "stop" -> {
                boolean force = tokens.length > 3 && tokens[3].equalsIgnoreCase("force");
                cloud.stop(service.id(), force);
                yield List.of(Reply.of(messages.text("cloud.service.stopped",
                        Map.of("name", service.name()))));
            }
            case "restart" -> {
                cloud.restart(service.id());
                yield List.of(Reply.of(messages.text("cloud.service.restarted",
                        Map.of("name", service.name()))));
            }
            case "kill" -> {
                cloud.stop(service.id(), true);
                yield List.of(Reply.of(messages.text("cloud.service.killed",
                        Map.of("name", service.name()))));
            }
            case "reapply" -> {
                cloud.reapply(service.id());
                yield List.of(Reply.of(messages.text("cloud.service.reapplied",
                        Map.of("name", service.name()))));
            }
            case "online" -> {
                cloud.online(service.id());
                yield List.of(Reply.of(messages.text("cloud.service.online",
                        Map.of("name", service.name()))));
            }
            case "exec" -> {
                String command = join(tokens, 3);
                if (command.isBlank()) {
                    yield List.of(Reply.of(messages.text("cloud.usageHint",
                            Map.of("usage", "/cloud service exec <name> <command>"))));
                }
                cloud.execute(service.id(), command);
                yield List.of(Reply.of(messages.text("cloud.service.exec",
                        Map.of("name", service.name(), "command", command))));
            }
            default -> List.of(Reply.of(messages.text("cloud.unknown",
                    Map.of("command", "service " + tokens[1]))));
        };
    }

    /**
     * {@code service restart-crashed}: startet alle abgestürzten Services neu.
     *
     * <p>Das ist das Ziel des einen Klick-Hinweises aus {@code status}: Ein Befehl
     * statt einer Zeile je Service. Ein Fehler bei einem Service bricht die
     * übrigen nicht ab — sonst bliebe nach dem ersten Problem der Rest liegen.
     *
     * @param messages Texte
     * @return die neu gestarteten Services, oder was fehlgeschlagen ist
     */
    private List<Reply> restartCrashed(Messages messages) {
        List<ServiceView> crashed = cloud.services().stream()
                .filter(service -> service.crash().crashed())
                .toList();
        if (crashed.isEmpty()) {
            return List.of(Reply.of(messages.text("cloud.service.restartCrashed.none", Map.of())));
        }
        List<String> restarted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (ServiceView service : crashed) {
            try {
                cloud.restart(service.id());
                restarted.add(service.name());
            } catch (CloudException e) {
                failed.add(service.name() + " (" + reasonOf(e) + ")");
            }
        }
        if (failed.isEmpty()) {
            return List.of(Reply.of(messages.text("cloud.service.restartCrashed", Map.of(
                    "count", String.valueOf(restarted.size()),
                    "names", String.join(", ", restarted)))));
        }
        return List.of(Reply.of(messages.text("cloud.service.restartCrashed.partial", Map.of(
                "restarted", restarted.isEmpty() ? "-" : String.join(", ", restarted),
                "failed", String.join(", ", failed)))));
    }

    private List<Reply> info(ServiceView service, Messages messages) {
        List<Reply> out = new ArrayList<>();
        out.add(Reply.of(messages.text("cloud.service.info", Map.of(
                "name", service.name(),
                "id", service.id(),
                "group", service.group(),
                "state", service.state(),
                "port", service.address(),
                "players", service.players(),
                "runtime", service.runtime()))));
        if (service.crash().crashed()) {
            out.add(Reply.of(messages.text("cloud.service.crash", Map.of(
                    "reason", service.crash().reason(),
                    "code", String.valueOf(service.crash().exitCode()),
                    "time", time(service.crash().time())))));
        }
        return out;
    }

    /**
     * {@code service logs}: die letzten Zeilen der Service-Konsole.
     *
     * @param service   Service
     * @param requested gewünschte Zeilenzahl, {@code null} für den Default
     * @param messages  Texte
     * @return Kopfzeile und Logzeilen, oder ein Hinweis bei leerem Puffer
     */
    private List<Reply> logs(ServiceView service, Integer requested, Messages messages) {
        int lines = requested == null || requested < 1
                ? DEFAULT_LOG_LINES
                : Math.min(requested, MAX_LOG_LINES);
        List<String> tail = cloud.logs(service.id(), lines);
        if (tail.isEmpty()) {
            return List.of(Reply.of(messages.text("cloud.service.logsEmpty",
                    Map.of("name", service.name()))));
        }
        List<Reply> out = new ArrayList<>(tail.size() + 1);
        out.add(Reply.of(messages.text("cloud.service.logsHeader", Map.of(
                "name", service.name(), "count", String.valueOf(tail.size())))));
        for (String line : tail) {
            out.add(Reply.of(line));
        }
        return out;
    }

    private List<Reply> serviceList(Messages messages) {
        List<ServiceView> services = cloud.services();
        if (services.isEmpty()) {
            return List.of(Reply.of(messages.text("cloud.status.empty", Map.of())));
        }
        List<Reply> out = new ArrayList<>();
        out.add(Reply.of(messages.text("cloud.list.header",
                Map.of("count", String.valueOf(services.size())))));
        for (ServiceView service : services) {
            out.add(Reply.of(row(service, messages)));
        }
        return out;
    }

    private List<Reply> group(String[] tokens, CloudPermissions permissions, Messages messages) {
        String usage = "/cloud group <list|info|scale>";
        if (tokens.length < 2) {
            return List.of(Reply.of(messages.text("cloud.usageHint", Map.of("usage", usage))));
        }
        String action = tokens[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            return guard("group.list", permissions, messages, () -> groupList(messages));
        }
        if (!action.equals("info") && !action.equals("scale")) {
            return List.of(Reply.of(messages.text("cloud.unknown",
                    Map.of("command", "group " + tokens[1]))));
        }
        if (!permissions.can("group." + action)) {
            return List.of(denied("group." + action, messages));
        }
        if (tokens.length < 3) {
            return List.of(Reply.of(messages.text("cloud.usageHint", Map.of("usage", usage))));
        }
        Optional<GroupView> found = findGroup(tokens[2]);
        if (found.isEmpty()) {
            return List.of(Reply.of(messages.text("cloud.unknownGroup",
                    Map.of("group", tokens[2]))));
        }
        GroupView group = found.get();
        if (action.equals("info")) {
            return List.of(Reply.of(messages.text("cloud.group.info", Map.of(
                    "name", group.name(),
                    "type", group.type(),
                    "mode", group.mode(),
                    "state", switchText(group.maintenance(), messages),
                    "services", String.valueOf(group.services()),
                    "max", String.valueOf(group.maxServices()),
                    "players", group.maxPlayers() <= 0
                            ? "-" : String.valueOf(group.maxPlayers())))));
        }
        if (tokens.length < 4) {
            return List.of(Reply.of(messages.text("cloud.usageHint",
                    Map.of("usage", "/cloud group scale <name> <anzahl>"))));
        }
        Integer target = number(tokens[3]);
        if (target == null || target < 0) {
            return List.of(Reply.of(messages.text("cloud.usageHint",
                    Map.of("usage", "/cloud group scale <name> <anzahl>"))));
        }
        cloud.scale(group.name(), target);
        return List.of(Reply.of(messages.text("cloud.group.scaled",
                Map.of("group", group.name(), "target", String.valueOf(target)))));
    }

    private List<Reply> groupList(Messages messages) {
        List<GroupView> groups = cloud.groups();
        if (groups.isEmpty()) {
            return List.of(Reply.of(messages.text("cloud.group.empty", Map.of())));
        }
        List<Reply> out = new ArrayList<>();
        out.add(Reply.of(messages.text("cloud.group.header",
                Map.of("count", String.valueOf(groups.size())))));
        for (GroupView group : groups) {
            out.add(Reply.of(groupRow(group, messages)));
        }
        return out;
    }

    private List<Reply> maintenance(String[] tokens, CloudPermissions permissions, Messages messages) {
        if (!permissions.can("maintenance")) {
            return List.of(denied("maintenance", messages));
        }
        String usage = "/cloud maintenance <gruppe> on|off";
        if (tokens.length < 3) {
            return List.of(Reply.of(messages.text("cloud.usageHint", Map.of("usage", usage))));
        }
        Boolean enabled = switch (tokens[2].toLowerCase(Locale.ROOT)) {
            case "on", "true", "an" -> Boolean.TRUE;
            case "off", "false", "aus" -> Boolean.FALSE;
            default -> null;
        };
        if (enabled == null) {
            return List.of(Reply.of(messages.text("cloud.usageHint", Map.of("usage", usage))));
        }
        Optional<GroupView> found = findGroup(tokens[1]);
        if (found.isEmpty()) {
            return List.of(Reply.of(messages.text("cloud.unknownGroup",
                    Map.of("group", tokens[1]))));
        }
        cloud.maintenance(found.get().name(), enabled);
        return List.of(Reply.of(messages.text("cloud.maintenance.set", Map.of(
                "group", found.get().name(),
                "state", switchText(enabled, messages)))));
    }

    private List<Reply> player(String[] tokens, CloudPermissions permissions, Messages messages) {
        if (tokens.length < 2) {
            return List.of(Reply.of(messages.text("cloud.usageHint",
                    Map.of("usage", "/cloud player <list|connect|kick>"))));
        }
        String action = tokens[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            return guard("player.list", permissions, messages, () -> playerList(messages));
        }
        if (!action.equals("connect") && !action.equals("kick")) {
            return List.of(Reply.of(messages.text("cloud.unknown",
                    Map.of("command", "player " + tokens[1]))));
        }
        if (!permissions.can("player." + action)) {
            return List.of(denied("player." + action, messages));
        }
        String usage = "/cloud player " + action + " <spieler>"
                + (action.equals("connect") ? " <service>" : " [grund]");
        if (tokens.length < 3) {
            return List.of(Reply.of(messages.text("cloud.usageHint", Map.of("usage", usage))));
        }
        Optional<PlayerView> found = findPlayer(tokens[2]);
        if (found.isEmpty()) {
            return List.of(Reply.of(messages.text("cloud.unknownPlayer",
                    Map.of("name", tokens[2]))));
        }
        PlayerView player = found.get();
        if (action.equals("connect")) {
            if (tokens.length < 4) {
                return List.of(Reply.of(messages.text("cloud.usageHint", Map.of("usage", usage))));
            }
            cloud.connect(player.uuid(), tokens[3]);
            return List.of(Reply.of(messages.text("cloud.player.connected", Map.of(
                    "player", player.name(), "service", tokens[3]))));
        }
        String reason = join(tokens, 3);
        if (reason.isBlank()) {
            reason = messages.text("cloud.player.defaultReason", Map.of());
        }
        cloud.kick(player.uuid(), reason);
        return List.of(Reply.of(messages.text("cloud.player.kicked",
                Map.of("player", player.name()))));
    }

    private List<Reply> playerList(Messages messages) {
        List<PlayerView> players = cloud.players();
        if (players.isEmpty()) {
            return List.of(Reply.of(messages.text("cloud.player.empty", Map.of())));
        }
        List<Reply> out = new ArrayList<>();
        out.add(Reply.of(messages.text("cloud.player.header",
                Map.of("count", String.valueOf(players.size())))));
        for (PlayerView player : players) {
            out.add(Reply.of(messages.text("cloud.player.row", Map.of(
                    "name", player.name(),
                    "proxy", player.proxy().isBlank() ? "-" : player.proxy(),
                    "server", player.server().isBlank() ? "-" : player.server()))));
        }
        return out;
    }

    private Optional<ServiceView> findService(String name) {
        return cloud.services().stream()
                .filter(service -> service.name().equalsIgnoreCase(name))
                .findFirst();
    }

    private Optional<GroupView> findGroup(String name) {
        return cloud.groups().stream()
                .filter(group -> group.name().equalsIgnoreCase(name))
                .findFirst();
    }

    private Optional<PlayerView> findPlayer(String name) {
        return cloud.players().stream()
                .filter(player -> player.name().equalsIgnoreCase(name))
                .findFirst();
    }

    private static String row(ServiceView service, Messages messages) {
        return messages.text("cloud.status.row", Map.of(
                "name", service.name(),
                "state", service.state(),
                "group", service.group(),
                "players", service.players(),
                "port", service.address(),
                "budget", service.crash().budget()));
    }

    private static String groupRow(GroupView group, Messages messages) {
        return messages.text("cloud.group.row", Map.of(
                "name", group.name(),
                "type", group.type(),
                "state", switchText(group.maintenance(), messages),
                "services", String.valueOf(group.services()),
                "max", String.valueOf(group.maxServices())));
    }

    private static String switchText(boolean enabled, Messages messages) {
        return messages.text(enabled ? "cloud.state.on" : "cloud.state.off", Map.of());
    }

    private static String time(long millis) {
        return millis <= 0 ? "-" : CRASH_TIME.format(Instant.ofEpochMilli(millis));
    }

    private static String join(String[] tokens, int from) {
        if (tokens.length <= from) {
            return "";
        }
        return String.join(" ", java.util.Arrays.copyOfRange(tokens, from, tokens.length)).trim();
    }

    private static Integer number(String token) {
        try {
            return Integer.valueOf(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Reply> lines(String text) {
        List<Reply> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            out.add(Reply.of(line));
        }
        return out;
    }

    private static String reasonOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
