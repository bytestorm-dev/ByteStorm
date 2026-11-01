package dev.bytestorm.bridge.bungeecord;

import dev.bytestorm.bridge.common.BridgeConfigurationException;
import dev.bytestorm.bridge.common.BridgeException;
import dev.bytestorm.bridge.common.BridgePlatform;
import dev.bytestorm.bridge.common.BridgeSettings;
import dev.bytestorm.bridge.common.BridgeText;
import dev.bytestorm.bridge.common.CloudBridge;
import dev.bytestorm.bridge.common.FallbackSelector;
import dev.bytestorm.proto.PlayerInfo;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceStateChanged;
import dev.bytestorm.proto.ServiceType;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ListenerInfo;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerKickEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * BungeeCord-Seite der Bridge (§15).
 *
 * <p>Der Proxy meldet sich mit denselben {@code BYTESTORM_*}-Variablen wie ein
 * Paper-Service an. Damit erscheint er in derselben Registry und kann später
 * dynamische Server registrieren sowie Fallback-Routing übernehmen.
 */
public final class BungeeBridgePlugin extends Plugin implements BridgePlatform, Listener {

    private final Set<String> registeredServers = ConcurrentHashMap.newKeySet();
    private final Map<String, ServiceInfo> onlineServices = new ConcurrentHashMap<>();

    private CloudBridge bridge;

    @Override
    public void onEnable() {
        getProxy().getPluginManager().registerListener(this, this);

        BridgeSettings settings;
        try {
            settings = BridgeSettings.fromEnvironment();
        } catch (BridgeConfigurationException e) {
            getLogger().log(Level.SEVERE, "ByteStorm-Bridge nicht konfiguriert: " + e.getMessage());
            return;
        }

        try {
            bridge = CloudBridge.start(settings, this);
            getLogger().log(Level.INFO, "ByteStorm-Bridge verbunden als " + settings.serviceName());
        } catch (BridgeException e) {
            getLogger().log(Level.SEVERE, "ByteStorm-Bridge konnte nicht starten: " + e.getMessage());
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
        return "bungeecord";
    }

    @Override
    public String platformVersion() {
        return getProxy().getVersion();
    }

    @Override
    public int onlinePlayers() {
        return getProxy().getPlayers().size();
    }

    @Override
    public int maxPlayers() {
        // Das Limit steht am Listener; getPlayerLimit() ist bei BungeeCord -1.
        return getProxy().getConfig().getListeners().stream()
                .findFirst()
                .map(ListenerInfo::getMaxPlayers)
                .filter(max -> max > 0)
                .orElseGet(() -> getProxy().getConfig().getPlayerLimit());
    }

    @Override
    public String motd() {
        // BungeeCord kennt keine globale MOTD, sondern eine je Listener.
        return getProxy().getConfig().getListeners().stream()
                .findFirst()
                .map(ListenerInfo::getMotd)
                .filter(motd -> motd != null && !motd.isEmpty())
                .orElse("");
    }

    @Override
    public void executeCommand(String command) {
        ProxyServer proxy = getProxy();
        proxy.getPluginManager().dispatchCommand(proxy.getConsole(), command);
    }

    @Override
    public void shutdown() {
        ProxyServer proxy = getProxy();
        // Der Aufruf kommt aus einem Redisson-Thread; der Scheduler übernimmt ihn.
        proxy.getScheduler().schedule(this, proxy::stop, 0, TimeUnit.MILLISECONDS);
    }

    @Override
    public void connectPlayer(String player, String target) {
        ProxyServer proxy = getProxy();
        ProxiedPlayer connected = proxy.getPlayer(player);
        if (connected == null) {
            getLogger().log(Level.WARNING, "Spieler {0} ist nicht verbunden", player);
            return;
        }
        ServerInfo server = proxy.getServers().get(target);
        if (server == null) {
            getLogger().log(Level.WARNING,
                    "Unbekannter Service {0} — {1} bleibt auf seinem Server", new Object[]{target, player});
            return;
        }
        // BungeeCord-API ist nicht thread-safe; der Scheduler übernimmt den Aufruf.
        proxy.getScheduler().schedule(this, () -> {
            connected.connect(server);
            getLogger().log(Level.INFO, "Spieler {0} wird nach {1} geschickt",
                    new Object[]{player, target});
        }, 0, TimeUnit.MILLISECONDS);
    }

    @Override
    public void kickPlayer(String player, String reason) {
        ProxyServer proxy = getProxy();
        ProxiedPlayer connected = proxy.getPlayer(player);
        if (connected == null) {
            getLogger().log(Level.WARNING, "Spieler {0} ist nicht verbunden", player);
            return;
        }
        String message = reason.isBlank() ? BridgeText.DEFAULT_KICK_REASON : reason;
        proxy.getScheduler().schedule(this, () -> {
            connected.disconnect(message);
            getLogger().log(Level.INFO, "Spieler {0} getrennt", player);
        }, 0, TimeUnit.MILLISECONDS);
    }

    /**
     * Meldet den Beitritt an die Cloud.
     *
     * @param event Login
     */
    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        if (bridge == null || !bridge.isRunning()) {
            return;
        }
        bridge.playerJoined(playerInfo(event.getPlayer()));
    }

    /**
     * Meldet den Austritt an die Cloud.
     *
     * @param event Disconnect
     */
    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        if (bridge == null || !bridge.isRunning()) {
            return;
        }
        bridge.playerLeft(playerInfo(event.getPlayer()));
    }

    private PlayerInfo playerInfo(ProxiedPlayer player) {
        var connection = player.getServer();
        ServerInfo current = connection == null ? null : connection.getInfo();
        return PlayerInfo.newBuilder()
                .setUuid(player.getUniqueId().toString())
                .setName(player.getName())
                .setProxy(bridge.settings().serviceName())
                .setServer(current == null ? "" : current.getName())
                .build();
    }

    /**
     * Leitet den ersten Verbindungsaufbau auf den besten Fallback-Server.
     *
     * <p>Ist das Ziel statisch in der Bungee-Config konfiguriert, bleibt die
     * Entscheidung des Betreibers unangetastet.
     *
     * @param event Verbindungsaufbau
     */
    @EventHandler
    public void onServerConnect(ServerConnectEvent event) {
        if (event.getPlayer().getServer() != null) {
            return;
        }
        ServerInfo target = event.getTarget();
        if (target == null || !registeredServers.contains(target.getName())) {
            return;
        }
        FallbackSelector.select(onlineServices.values())
                .map(service -> getProxy().getServers().get(service.getName()))
                .ifPresent(event::setTarget);
    }

    /**
     * Fängt einen Kick ab und verbindet den Spieler mit dem nächsten
     * Fallback-Server, statt ihn auf den Hub zu werfen.
     *
     * @param event Kick vom aktuellen Server
     */
    @EventHandler
    public void onServerKick(ServerKickEvent event) {
        if (event.isCancelled() || event.getKickedFrom() == null) {
            return;
        }
        FallbackSelector.select(onlineServices.values(), event.getKickedFrom().getName())
                .map(service -> getProxy().getServers().get(service.getName()))
                .ifPresent(target -> {
                    event.setCancelled(true);
                    event.setCancelServer(target);
                });
    }

    @Override
    public void onStateChanged(ServiceStateChanged event) {
        ServiceInfo service = event.getService();
        if (service.getType() != ServiceType.SERVER
                || service.getPort() <= 0 || service.getPort() > 65535) {
            return;
        }
        switch (service.getState()) {
            case ONLINE -> register(service);
            case STOPPING, STOPPED, CRASHED -> unregister(service.getName());
            default -> {
                // PREPARING/STARTING: noch nicht erreichbar.
            }
        }
    }

    private synchronized void register(ServiceInfo service) {
        // BungeeCord hat kein registerServer; getServers() ist die live Map.
        Map<String, ServerInfo> servers = getProxy().getServers();
        // Statisch konfigurierte Server bleiben unangetastet.
        if (servers.containsKey(service.getName())) {
            return;
        }
        ServerInfo info = getProxy().constructServerInfo(service.getName(),
                new InetSocketAddress(service.getHost(), service.getPort()), "ByteStorm", false);
        if (servers.putIfAbsent(service.getName(), info) == null) {
            registeredServers.add(service.getName());
            onlineServices.put(service.getName(), service);
            getLogger().log(Level.INFO, "Service {0} als Server {1}:{2} registriert",
                    new Object[]{service.getName(), service.getHost(), service.getPort()});
        }
    }

    private synchronized void unregister(String name) {
        onlineServices.remove(name);
        if (!registeredServers.remove(name)) {
            return;
        }
        getProxy().getServers().remove(name);
        getLogger().log(Level.INFO, "Service {0} als Server entfernt", name);
    }
}
