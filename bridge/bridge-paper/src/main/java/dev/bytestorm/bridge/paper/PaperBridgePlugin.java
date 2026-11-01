package dev.bytestorm.bridge.paper;

import dev.bytestorm.bridge.common.BridgeConfigurationException;
import dev.bytestorm.bridge.common.BridgeException;
import dev.bytestorm.bridge.common.BridgePlatform;
import dev.bytestorm.bridge.common.BridgeSettings;
import dev.bytestorm.bridge.common.CloudBridge;
import dev.bytestorm.bridge.paper.placeholder.PlaceholderValues;
import dev.bytestorm.bridge.paper.placeholder.Placeholders;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceStateChanged;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Map<String, ServiceInfo> services = new ConcurrentHashMap<>();

    private CloudBridge bridge;
    private String serviceName = "";
    private Object placeholders;

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

        serviceName = settings.serviceName();
        try {
            bridge = CloudBridge.start(settings, this);
            getLogger().info("ByteStorm-Bridge verbunden als " + settings.serviceName());
        } catch (BridgeException e) {
            getLogger().severe("ByteStorm-Bridge konnte nicht starten: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        registerPlaceholders();
    }

    @Override
    public void onDisable() {
        unregisterPlaceholders();
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
        services.clear();
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
        // den geordneten Shutdown nur verdoppeln. Die Bridge merkt sich den
        // Zustand nur, damit die Platzhalter Netzwerkzahlen liefern können.
        ServiceInfo service = event.getService();
        if (service.getName().isBlank()) {
            return;
        }
        if (service.getState() == dev.bytestorm.proto.ServiceState.STOPPED) {
            // Die Cloud entfernt den Eintrag; ein gestoppter Service soll die
            // Summen nicht mit alten Spielerzahlen verfälschen.
            services.remove(service.getName());
            return;
        }
        services.put(service.getName(), service);
    }

    /**
     * @return die zuletzt bekannten Services für die Platzhalter
     */
    Collection<ServiceInfo> services() {
        return List.copyOf(services.values());
    }

    /**
     * Meldet {@code %bytestorm_...%} an, wenn PlaceholderAPI installiert ist.
     * Ohne das Plugin bleibt die Klasse mit den PlaceholderAPI-Typen ungeladen.
     */
    private void registerPlaceholders() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        PlaceholderValues values = new PlaceholderValues(this::services, this::localValues);
        placeholders = Placeholders.register(this, values);
        getLogger().info("PlaceholderAPI gefunden — %bytestorm_...% angemeldet");
    }

    private void unregisterPlaceholders() {
        Object current = placeholders;
        placeholders = null;
        if (current != null) {
            Placeholders.unregister(current);
        }
    }

    /**
     * @return die Werte dieses Servers; sie ändern sich mit jedem Tick
     */
    private PlaceholderValues.Local localValues() {
        return new PlaceholderValues.Local(serviceName, platformName(), platformVersion(),
                onlinePlayers(), maxPlayers(), motd());
    }
}
