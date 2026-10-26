package dev.bytestorm.cli;

import dev.bytestorm.controller.player.PlayerManager;
import dev.bytestorm.controller.player.PlayerManager.PlayerDetail;
import dev.bytestorm.core.mongo.model.StoredPlayer;
import dev.bytestorm.proto.PlayerInfo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

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
            return USAGE.stripTrailing();
        }
        String command = tokens[1].toLowerCase(Locale.ROOT);
        try {
            return switch (command) {
                case "help" -> USAGE.stripTrailing();
                case "list" -> list();
                case "info" -> tokens.length < 3 ? "Spielername fehlt (player info <name>)" : info(tokens[2]);
                case "connect" -> connect(tokens);
                case "kick" -> kick(tokens);
                default -> "Unbekanntes Spieler-Kommando: " + command + NL + USAGE.stripTrailing();
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "Fehler: " + e.getMessage();
        } catch (RuntimeException e) {
            return "Fehler: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
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
            return "Keine Spieler online.";
        }
        StringJoiner out = new StringJoiner(NL);
        out.add("Name\tUUID\tProxy\tServer");
        for (PlayerInfo player : online) {
            out.add(player.getName() + "\t" + player.getUuid() + "\t"
                    + (player.getProxy().isBlank() ? "-" : player.getProxy()) + "\t"
                    + (player.getServer().isBlank() ? "-" : player.getServer()));
        }
        return out.toString();
    }

    private String info(String name) {
        Optional<PlayerDetail> found = players.find(name);
        if (found.isEmpty()) {
            return "Unbekannter Spieler: " + name + " (weder online noch in der Historie)";
        }
        PlayerDetail detail = found.get();
        StringBuilder out = new StringBuilder("Name: ").append(name).append(NL);
        if (detail.online()) {
            PlayerInfo live = detail.live();
            out.append("UUID: ").append(live.getUuid()).append(NL)
                    .append("Status: online").append(NL)
                    .append("Proxy: ").append(text(live.getProxy())).append(NL)
                    .append("Server: ").append(text(live.getServer()));
        } else {
            out.append("Status: nicht online");
        }
        StoredPlayer stored = detail.stored();
        if (stored == null) {
            out.append(NL).append("Historie: unbekannt");
            return out.toString();
        }
        out.append(NL).append("Historie:").append(NL)
                .append("  UUID: ").append(stored.uuid()).append(NL)
                .append("  Spielzeit: ").append(ServiceCommandLine.formatUptime(stored.playtime())).append(NL)
                .append("  Erster Join: ").append(timestamp(stored.firstSeen())).append(NL)
                .append("  Zuletzt gesehen: ").append(timestamp(stored.lastSeen())).append(NL)
                .append("  Letzter Service: ").append(text(stored.lastService()));
        return out.toString();
    }

    private String connect(String[] tokens) {
        if (tokens.length < 4) {
            return "Ziel fehlt (player connect <name> <service>)";
        }
        String target = tokens[3].trim();
        if (target.split("\\s+").length > 1) {
            return "Erwartet genau einen Service: player connect " + tokens[2] + " <service>";
        }
        String proxy = players.connect(tokens[2], target);
        return "Spieler '" + tokens[2] + "' wird über " + proxy + " nach '" + target
                + "' geschickt.";
    }

    private String kick(String[] tokens) {
        if (tokens.length < 3) {
            return "Spielername fehlt (player kick <name> [grund])";
        }
        String reason = tokens.length > 3 ? tokens[3].trim() : "";
        String proxy = players.kick(tokens[2], reason);
        return "Spieler '" + tokens[2] + "' wird über " + proxy + " getrennt"
                + (reason.isEmpty() ? "." : ": " + reason);
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
