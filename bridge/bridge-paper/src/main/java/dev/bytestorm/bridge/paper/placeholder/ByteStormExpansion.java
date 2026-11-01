package dev.bytestorm.bridge.paper.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI-Erweiterung der Bridge (§15, §16).
 *
 * <p>Sie meldet sich als {@code bytestorm} an, alle Platzhalter heißen also
 * {@code %bytestorm_<name>%}. Die Werte kommen aus {@link PlaceholderValues};
 * diese Klasse übersetzt nur noch zwischen PlaceholderAPI und der Cloud-Sicht.
 *
 * <p>{@code persist()} ist gesetzt, damit {@code /papi reload} die Erweiterung
 * nicht abmeldet. Sie wird nur geladen, wenn PlaceholderAPI installiert ist —
 * die Bridge läuft auch ohne.
 */
public final class ByteStormExpansion extends PlaceholderExpansion {

    /** Präfix aller Platzhalter. */
    public static final String IDENTIFIER = "bytestorm";

    private final Plugin plugin;
    private final PlaceholderValues values;

    /**
     * @param plugin Bridge-Plugin, liefert Name und Version
     * @param values rechnet die Platzhalter aus
     */
    public ByteStormExpansion(Plugin plugin, PlaceholderValues values) {
        this.plugin = plugin;
        this.values = values;
    }

    @Override
    public @NotNull String getIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public @NotNull String getAuthor() {
        return "ByteStorm";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        return values.value(params);
    }
}
