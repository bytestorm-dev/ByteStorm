package dev.bytestorm.cli;

import dev.bytestorm.controller.player.PlayerManager;
import dev.bytestorm.controller.player.PlayerManager.PlayerDetail;
import dev.bytestorm.core.mongo.model.StoredPlayer;
import dev.bytestorm.proto.PlayerInfo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Spieler-Kommandos der CLI (§14, §17).
 *
 * <p>Anders als die Service- und Gruppen-Kommandos bekommt dieser Dispatcher die
 * ganze Zeile statt einzelner Tokens: {@code player kick <name> <grund>} darf
 * Leerzeichen in der Begründung enthalten.
 */
public final class PlayerCommandLine {

    /** Hilfe für die Spieler-Kommandos. */
    public static final String USAGE = """
            player list                       alle Spieler, die gerade online sind
            player info <name>                Live-Zustand und Historie eines Spielers
            player connect <name> <service>   Spieler auf einen anderen Service schicken
            player kick <name> [grund]        Spieler vom Netz trennen
            player help                       diese Hilfe

            Die Live-Daten kommen aus der Registry der Proxies, die Historie aus MongoDB.
            `connect` und `kick` gehen an den Proxy, auf dem der Spieler verbunden ist —
            nur er kann den Spieler umschalten oder trennen. Das Ziel muss ein
            laufender Server-Service sein (`service list` zeigt alle Services).
            """;

    private static final String NL = System.lineSeparator();

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final PlayerManager players;

    /**
     * @param players Spieler-Verwaltung
     */
    public PlayerCommandLine(PlayerManager players) {
        this.players = Objects.requireNonNull(players, "players");
    }

    /**
     * Führt ein Spieler-Kommando aus.
     *
     * @param line vollständige Zeile, z. B. {@code player kick Bob zu viele Kicks}
     * @return Ausgabe für das Terminal
     */
    public String execute(String line) {
        String[] tokens = line.trim().split("\\s+", 4);
        if (tokens.length < 2) {
            return Theme.help(USAGE.stripTrailing());
        }
        String command = tokens[1].toLowerCase(Locale.ROOT);
        try {
            return switch (command) {
                case "help" -> Theme.help(USAGE.stripTrailing());
                case "list" -> list();
                case "info" -> tokens.length < 3
                        ? Theme.warn("Spielername fehlt (player info <name>)")
                        : info(tokens[2]);
                case "connect" -> connect(tokens);
                case "kick" -> kick(tokens);
                default -> Theme.warn("Unbekanntes Spieler-Kommando: " + command) + NL
                        + Theme.help(USAGE.stripTrailing());
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Theme.fail("Fehler: " + e.getMessage());
        } catch (RuntimeException e) {
            return Theme.fail("Fehler: ") + (e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * @return die Namen der online Spieler, alphabetisch; leer wenn Redis nicht antwortet
     */
    public List<String> names() {
        try {
            return players.online().stream().map(PlayerInfo::getName).toList();
        } catch (RuntimeException e) {
            // Die Tab-Vervollständigung darf an einem Fehler nicht scheitern.
            return List.of();
        }
    }

    private String list() {
        List<PlayerInfo> online = players.online();
        if (online.isEmpty()) {
            return Theme.dim("Keine Spieler online.");
        }
        List<PlayerInfo> sorted = new ArrayList<>(online);
        sorted.sort(Comparator.comparing(PlayerInfo::getName, String.CASE_INSENSITIVE_ORDER));
        Table table = Table.of("Name", "UUID", "Proxy", "Server");
        for (PlayerInfo player : sorted) {
            table.row(Theme.bold(player.getName()), player.getUuid(),
                    text(player.getProxy()), text(player.getServer()));
        }
        return table.render();
    }

    private String info(String name) {
        Optional<PlayerDetail> found = players.find(name);
        if (found.isEmpty()) {
            return Theme.warn("Unbekannter Spieler: " + name
                    + " (weder online noch in der Historie)");
        }
        PlayerDetail detail = found.get();
        StringBuilder out = new StringBuilder(Theme.field("Name", Theme.bold(name))).append(NL);
        if (detail.online()) {
            PlayerInfo live = detail.live();
            out.append(Theme.field("UUID", live.getUuid())).append(NL)
                    .append(Theme.field("Status", Theme.green("online"))).append(NL)
                    .append(Theme.field("Proxy", text(live.getProxy()))).append(NL)
                    .append(Theme.field("Server", text(live.getServer())));
        } else {
            out.append(Theme.field("Status", Theme.dim("nicht online")));
        }
        StoredPlayer stored = detail.stored();
        if (stored == null) {
            out.append(NL).append(Theme.field("Historie", Theme.dim("unbekannt")));
            return out.toString();
        }
        out.append(NL).append(Theme.section("Historie")).append(NL)
                .append(Theme.field("  UUID", String.valueOf(stored.uuid()))).append(NL)
                .append(Theme.field("  Spielzeit", ServiceCommandLine.formatUptime(stored.playtime()))).append(NL)
                .append(Theme.field("  Erster Join", timestamp(stored.firstSeen()))).append(NL)
                .append(Theme.field("  Zuletzt gesehen", timestamp(stored.lastSeen()))).append(NL)
                .append(Theme.field("  Letzter Service", text(stored.lastService())));
        return out.toString();
    }

    private String connect(String[] tokens) {
        if (tokens.length < 4) {
            return Theme.warn("Ziel fehlt (player connect <name> <service>)");
        }
        String target = tokens[3].trim();
        if (target.split("\\s+").length > 1) {
            return Theme.warn("Erwartet genau einen Service: player connect " + tokens[2] + " <service>");
        }
        String proxy = players.connect(tokens[2], target);
        return Theme.ok("Spieler '" + tokens[2] + "' wird über " + proxy + " nach '" + target
                + "' geschickt.");
    }

    private String kick(String[] tokens) {
        if (tokens.length < 3) {
            return Theme.warn("Spielername fehlt (player kick <name> [grund])");
        }
        String reason = tokens.length > 3 ? tokens[3].trim() : "";
        String proxy = players.kick(tokens[2], reason);
        return Theme.ok("Spieler '" + tokens[2] + "' wird über " + proxy + " getrennt"
                + (reason.isEmpty() ? "." : ": " + reason));
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String timestamp(Instant instant) {
        if (instant == null || instant.equals(Instant.EPOCH)) {
            return "-";
        }
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(TIMESTAMP);
    }
}
