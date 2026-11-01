package dev.bytestorm.bridge.paper.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.plugin.Plugin;

/**
 * Brücke zu PlaceholderAPI.
 *
 * <p>Die Klasse ist die einzige Stelle, die PlaceholderAPI-Typen anfasst. Das
 * Bridge-Plugin ruft sie nur an, wenn die Server-API das Plugin
 * {@code PlaceholderAPI} meldet — ohne es wird diese Klasse nie geladen und die
 * Bridge startet trotzdem.
 */
public final class Placeholders {

    private Placeholders() {
    }

    /**
     * Meldet die Erweiterung an.
     *
     * @param plugin  Bridge-Plugin
     * @param values  rechnet die Platzhalter aus
     * @return die angemeldete Erweiterung, für {@link #unregister(Object)}
     */
    public static Object register(Plugin plugin, PlaceholderValues values) {
        ByteStormExpansion expansion = new ByteStormExpansion(plugin, values);
        expansion.register();
        return expansion;
    }

    /**
     * Meldet eine zuvor angemeldete Erweiterung wieder ab.
     *
     * @param expansion Ergebnis von {@link #register(Plugin, PlaceholderValues)}
     */
    public static void unregister(Object expansion) {
        if (expansion instanceof PlaceholderExpansion placeholderExpansion) {
            placeholderExpansion.unregister();
        }
    }
}
