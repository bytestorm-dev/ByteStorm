package dev.bytestorm.plugins.lobby.display;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Speichert die Server-Anzeigen in {@code displays.yml}.
 *
 * <p>Die Datei ist die Quelle der Wahrheit, nicht die Entities: Displays werden
 * grundsätzlich nicht mit dem Chunk gespeichert, sonst bliebe nach einem Absturz
 * ein halbes Hologramm zurück. Beim Start liest das Plugin die Datei und baut die
 * Entities neu auf.
 */
public final class DisplayStore {

    private static final Logger LOGGER = Logger.getLogger("ByteStormLobby");
    private static final String ROOT = "displays";

    private final File file;

    /**
     * @param plugin Plugin, dessen Datenverzeichnis die Datei aufnimmt
     */
    public DisplayStore(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "displays.yml");
    }

    /**
     * @return alle gespeicherten Anzeigen, nach ID sortiert
     */
    public List<ServerDisplay> load() {
        if (!file.isFile()) {
            return List.of();
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<Map<?, ?>> entries = yaml.getMapList(ROOT);
        List<ServerDisplay> displays = new ArrayList<>(entries.size());
        for (Map<?, ?> entry : entries) {
            ServerDisplay display = parse(entry);
            if (display != null) {
                displays.add(display);
            }
        }
        displays.sort(Comparator.comparingInt(ServerDisplay::id));
        return displays;
    }

    /**
     * Schreibt die Liste vollständig neu.
     *
     * @param displays aktuelle Anzeigen
     */
    public void save(Collection<ServerDisplay> displays) {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> entries = new ArrayList<>(displays.size());
        for (ServerDisplay display : displays) {
            entries.add(Map.of(
                    "id", display.id(),
                    "group", display.group(),
                    "name", display.displayName(),
                    "head", display.headId() == null ? "" : display.headId(),
                    "world", display.world(),
                    "x", display.x(),
                    "y", display.y(),
                    "z", display.z(),
                    "yaw", (double) display.yaw(),
                    "pitch", (double) display.pitch()));
        }
        yaml.set(ROOT, entries);

        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                LOGGER.warning("Datenverzeichnis konnte nicht angelegt werden: " + parent);
            }
            yaml.save(file);
        } catch (IOException e) {
            LOGGER.severe("displays.yml konnte nicht geschrieben werden: " + e.getMessage());
        }
    }

    /**
     * @param entry ein Eintrag aus der Datei
     * @return die Anzeige oder {@code null}, wenn Pflichtfelder fehlen
     */
    private ServerDisplay parse(Map<?, ?> entry) {
        Integer id = number(entry.get("id"), Integer.class);
        Object group = entry.get("group");
        Object world = entry.get("world");
        if (id == null || group == null || world == null) {
            LOGGER.warning("Eintrag in displays.yml ohne id, group oder world — übersprungen");
            return null;
        }

        Object name = entry.get("name");
        Object head = entry.get("head");
        return new ServerDisplay(
                id,
                group.toString(),
                name == null || name.toString().isBlank() ? group.toString() : name.toString(),
                head == null || head.toString().isBlank() ? null : head.toString(),
                world.toString(),
                number(entry.get("x"), Double.class, 0.0),
                number(entry.get("y"), Double.class, 0.0),
                number(entry.get("z"), Double.class, 0.0),
                number(entry.get("yaw"), Double.class, 0.0).floatValue(),
                number(entry.get("pitch"), Double.class, 0.0).floatValue());
    }

    private static <T extends Number> T number(Object value, Class<T> type) {
        return number(value, type, null);
    }

    private static <T extends Number> T number(Object value, Class<T> type, T fallback) {
        if (value == null) {
            return fallback;
        }
        if (type == Integer.class && value instanceof Number number) {
            return type.cast(number.intValue());
        }
        if (type == Double.class && value instanceof Number number) {
            return type.cast(number.doubleValue());
        }
        try {
            return type.cast(type == Integer.class
                    ? Integer.valueOf(value.toString())
                    : Double.valueOf(value.toString()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
