package dev.bytestorm.launcher.luckperms;

import java.util.Locale;
import java.util.Optional;

/**
 * Zuordnung einer Cloud-Plattform zu ihrem LuckPerms-Paket (§13).
 *
 * <p>LuckPerms liefert je Server-Software ein eigenes Jar, und jede Software
 * erwartet es an einer anderen Stelle: Bukkit-Derivate und Proxys laden aus
 * {@code plugins/}, Mod-Loader und Sponge aus {@code mods/}. Auch die
 * Konfiguration liegt nicht überall gleich — Velocity schreibt klein
 * {@code plugins/luckperms/config.yml}, Fabric, Forge, NeoForge und Sponge lesen
 * HOCON aus {@code config/luckperms/luckperms.conf}.
 *
 * <p>Der Schlüssel ist der Name aus der Download-Metadaten-API
 * ({@code https://metadata.luckperms.net/data/downloads}).
 */
public enum LuckPermsPlatform {

    /** Bukkit, Spigot, Paper, Purpur, Folia und andere Bukkit-Derivate. */
    BUKKIT("bukkit", "Bukkit/Paper", "plugins", "plugins/LuckPerms/config.yml", true),

    /** BungeeCord, Waterfall und Travertine. */
    BUNGEE("bungee", "BungeeCord", "plugins", "plugins/LuckPerms/config.yml", true),

    /** Velocity. */
    VELOCITY("velocity", "Velocity", "plugins", "plugins/luckperms/config.yml", true),

    /** Fabric. */
    FABRIC("fabric", "Fabric", "mods", "config/luckperms/luckperms.conf", false),

    /** Forge. */
    FORGE("forge", "Forge", "mods", "config/luckperms/luckperms.conf", false),

    /** NeoForge. */
    NEOFORGE("neoforge", "NeoForge", "mods", "config/luckperms/luckperms.conf", false),

    /** Sponge. */
    SPONGE("sponge", "Sponge", "mods", "config/luckperms/luckperms.conf", false);

    private final String key;
    private final String label;
    private final String folder;
    private final String configPath;
    private final boolean yaml;

    LuckPermsPlatform(String key, String label, String folder, String configPath, boolean yaml) {
        this.key = key;
        this.label = label;
        this.folder = folder;
        this.configPath = configPath;
        this.yaml = yaml;
    }

    /**
     * @param platform Plattform aus der Gruppe, z. B. {@code paper}
     * @return die passende LuckPerms-Variante, leer wenn LuckPerms sie nicht anbietet
     */
    public static Optional<LuckPermsPlatform> forCloudPlatform(String platform) {
        if (platform == null) {
            return Optional.empty();
        }
        String normalized = platform.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "paper", "spigot", "bukkit", "craftbukkit", "purpur", "folia", "pufferfish", "leaf" ->
                    Optional.of(BUKKIT);
            case "velocity" -> Optional.of(VELOCITY);
            case "bungeecord", "bungee", "waterfall", "travertine" -> Optional.of(BUNGEE);
            case "fabric" -> Optional.of(FABRIC);
            case "forge" -> Optional.of(FORGE);
            case "neoforge" -> Optional.of(NEOFORGE);
            case "sponge", "spongevanilla" -> Optional.of(SPONGE);
            default -> Optional.empty();
        };
    }

    /**
     * @return Schlüssel in der Download-API
     */
    public String key() {
        return key;
    }

    /**
     * @return lesbarer Name für Log und Fehlermeldungen
     */
    public String label() {
        return label;
    }

    /**
     * @return Unterordner im Service, {@code plugins} oder {@code mods}
     */
    public String folder() {
        return folder;
    }

    /**
     * @return Pfad der Konfiguration, relativ zum Service-Ordner
     */
    public String configPath() {
        return configPath;
    }

    /**
     * @return {@code true}, wenn LuckPerms hier {@code config.yml} im YAML-Format liest
     */
    public boolean yaml() {
        return yaml;
    }

    /**
     * @return Dateiname der Jar im Service-Ordner
     */
    public String jarName() {
        return "luckperms-" + key + ".jar";
    }
}
