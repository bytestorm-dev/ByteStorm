package dev.bytestorm.bridge.paper;

import dev.bytestorm.bridge.common.BridgeConfigurationException;
import dev.bytestorm.bridge.common.BridgeException;
import dev.bytestorm.bridge.common.BridgePlatform;
import dev.bytestorm.bridge.common.BridgeSettings;
import dev.bytestorm.bridge.common.CloudBridge;
import dev.bytestorm.proto.ServiceStateChanged;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Paper-Seite der Bridge (§15).
 *
 * <p>Die Cloud startet den Service mit den {@code BYTESTORM_*}-Variablen; das
 * Plugin baut daraus die Verbindung auf, meldet den Service als bereit und
 * leitet Konsolenbefehle sowie Stop-Anfragen an den Server weiter. Der Server
 * bleibt die Instanz, die Spielerzahlen und MOTD liefert.
 *
 * <p>Server-API-Aufrufe aus Redisson-Threads werden auf den Global-Region-
 * Scheduler verschoben; das funktioniert auf Paper und Folia.
 */
public final class PaperBridgePlugin extends JavaPlugin implements BridgePlatform {

    private CloudBridge bridge;

    @Override
    public void onEnable() {
        BridgeSettings settings;
        try {
            settings = BridgeSettings.fromEnvironment();
        } catch (BridgeConfigurationException e) {
            getLogger().severe("ByteStorm-Bridge nicht konfiguriert: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            bridge = CloudBridge.start(settings, this);
            getLogger().info("ByteStorm-Bridge verbunden als " + settings.serviceName());
        } catch (BridgeException e) {
            getLogger().severe("ByteStorm-Bridge konnte nicht starten: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
    }

    @Override
    public String platformName() {
        return "paper";
    }

    @Override
    public String platformVersion() {
        return Bukkit.getMinecraftVersion();
    }

    @Override
    public int onlinePlayers() {
        return Bukkit.getOnlinePlayers().size();
    }

    @Override
    public int maxPlayers() {
        return Bukkit.getMaxPlayers();
    }

    @Override
    public String motd() {
        return Bukkit.getMotd();
    }

    @Override
    public void executeCommand(String command) {
        Bukkit.getGlobalRegionScheduler().execute(this,
                () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    @Override
    public void shutdown() {
        Bukkit.getGlobalRegionScheduler().execute(this, Bukkit::shutdown);
    }

    @Override
    public void onStateChanged(ServiceStateChanged event) {
        // Der Node stoppt den Prozess selbst; ein zusätzliches Eingreifen würde
        // den geordneten Shutdown nur verdoppeln.
    }
}
