package dev.bytestorm.plugins.proxy.command;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Berechtigungen der {@code /cloud}-Kommandos.
 *
 * <p>Jedes Kommando hat eine eigene Node unter {@code bytestorm.command}.
 * Unterbefehle werden über Wildcards zusammengefasst, damit ein Rang nicht
 * zwanzig Nodes braucht:
 *
 * <ul>
 *   <li>{@code bytestorm.command.player.kick} — genau dieser Befehl</li>
 *   <li>{@code bytestorm.command.player.*} — alle Spieler-Befehle</li>
 *   <li>{@code bytestorm.command.kick.*} — derselbe Befehl in jeder Gruppe</li>
 *   <li>{@code bytestorm.command.*} — jedes Kommando</li>
 * </ul>
 *
 * <p>Die Klasse kennt weder Velocity noch die Konfiguration: Sie fragt nur eine
 * Prüfung, ob eine Node vergeben ist. Dadurch ist die Auflösung der Wildcards
 * ohne laufenden Proxy testbar, und ein Rang, der nur {@code player.kick}
 * darf, kann keine Services stoppen.
 */
public final class CloudPermissions {

    /** Gemeinsamer Anfang aller Kommando-Nodes. */
    public static final String PREFIX = "bytestorm.command";

    /** Wildcard, die jedes Kommando erlaubt. */
    public static final String WILDCARD = PREFIX + ".*";

    private final Predicate<String> granted;

    /**
     * @param granted prüft, ob eine einzelne Node vergeben ist
     */
    public CloudPermissions(Predicate<String> granted) {
        this.granted = Objects.requireNonNull(granted, "granted");
    }

    /**
     * @return Prüfung, die alles erlaubt — für Tests und für den Admin-Override
     */
    public static CloudPermissions allowAll() {
        return new CloudPermissions(node -> true);
    }

    /**
     * @param path Kommandopfad, z. B. {@code player.kick}
     * @return die Node dieses Kommandos, z. B. {@code bytestorm.command.player.kick}
     */
    public static String node(String path) {
        return PREFIX + "." + path;
    }

    /**
     * @param path Kommandopfad, z. B. {@code player.kick}
     * @return alle Nodes, von denen eine dieses Kommando erlaubt, spezifischste zuerst
     */
    public static List<String> candidates(String path) {
        Objects.requireNonNull(path, "path");
        String[] parts = path.split("\\.");
        Set<String> nodes = new LinkedHashSet<>();
        nodes.add(node(path));
        for (int length = parts.length - 1; length >= 1; length--) {
            nodes.add(PREFIX + "." + String.join(".", Arrays.copyOfRange(parts, 0, length)) + ".*");
        }
        nodes.add(PREFIX + "." + parts[parts.length - 1] + ".*");
        nodes.add(WILDCARD);
        return List.copyOf(nodes);
    }

    /**
     * @param path Kommandopfad, z. B. {@code player.kick}
     * @return {@code true}, wenn eine der Nodes dieses Kommandos vergeben ist
     */
    public boolean can(String path) {
        for (String candidate : candidates(path)) {
            if (granted.test(candidate)) {
                return true;
            }
        }
        return false;
    }
}
