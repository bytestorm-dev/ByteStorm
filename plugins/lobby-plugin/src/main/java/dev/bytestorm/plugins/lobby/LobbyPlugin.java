package dev.bytestorm.plugins.lobby;

import dev.bytestorm.plugins.lobby.command.DisplayCommand;
import dev.bytestorm.plugins.lobby.config.LobbySettings;
import dev.bytestorm.plugins.lobby.display.DisplayStore;
import dev.bytestorm.plugins.lobby.display.ServerDisplayService;
import dev.bytestorm.plugins.lobby.listener.DisplayListener;
import dev.bytestorm.plugins.lobby.redis.LiveState;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

/**
 * Lobby-Seite der Server-Auswahl (§14).
 *
 * <p>Das Plugin stellt je Service einen schwebenden Kopf mit Hologramm in die
 * Welt; ein Klick fordert den Wechsel beim Proxy an. Es braucht weder die Cloud
 * noch die Bridge im selben Prozess — nur Redis, dessen Zugang der Node jedem
 * Service mitgibt.
 *
 * <p>Fehlt Redis oder die Adresse, schaltet sich das Plugin mit einer klaren
 * Meldung ab. Ein Lobby-Server, der halb läuft und bei jedem Klick schweigt, wäre
 * schwerer zu diagnostizieren als einer, der gar nicht erst startet.
 */
public final class LobbyPlugin extends JavaPlugin {

    private LobbySettings settings;
    private LiveState live;
    private ServerDisplayService displays;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            settings = LobbySettings.from(getConfig(), System.getenv());
        } catch (IllegalArgumentException e) {
            getLogger().severe("Lobby-Anzeigen nicht konfiguriert: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            live = LiveState.connect(settings);
        } catch (RuntimeException e) {
            getLogger().severe("Redis nicht erreichbar: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        displays = new ServerDisplayService(this, live, new DisplayStore(this));
        displays.start();

        PluginCommand command = getCommand("serverdisplay");
        if (command != null) {
            DisplayCommand executor = new DisplayCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getServer().getPluginManager().registerEvents(new DisplayListener(displays), this);

        getLogger().info("Server-Anzeigen bereit (" + displays.displays().size()
                + " aus displays.yml, Redis " + settings.redisAddress() + ")");
    }

    @Override
    public void onDisable() {
        if (displays != null) {
            displays.shutdown();
            displays = null;
        }
        if (live != null) {
            live.close();
            live = null;
        }
    }

    /**
     * @return geprüfte Einstellungen, {@code null} solange das Plugin nicht aktiv ist
     */
    public @Nullable LobbySettings settings() {
        return settings;
    }

    /**
     * @return der Anzeigen-Service, {@code null} solange das Plugin nicht aktiv ist
     */
    public @Nullable ServerDisplayService displays() {
        return displays;
    }

    /**
     * @return der Lesezugriff auf Redis, {@code null} solange das Plugin nicht aktiv ist
     */
    public @Nullable LiveState live() {
        return live;
    }
}
