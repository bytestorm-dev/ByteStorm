package dev.bytestorm.bridge.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import dev.bytestorm.api.bridge.ProxyDefaults;
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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Velocity-Seite der Bridge (§15).
 *
 * <p>Der Proxy meldet sich mit denselben {@code BYTESTORM_*}-Variablen wie ein
 * Paper-Service an. Damit erscheint er in derselben Registry und kann später
 * dynamische Server registrieren sowie Fallback-Routing übernehmen.
 */
@Plugin(id = "bytestorm-bridge", name = "ByteStormBridge", version = "1.0-SNAPSHOT",
        description = "Verbindet einen Velocity-Proxy mit der ByteStorm-Cloud.",
        authors = {"ByteStorm"})
public final class VelocityBridgePlugin implements BridgePlatform {

    private final ProxyServer server;
    private final Logger logger;
    private final Map<String, ServerInfo> registeredServers = new ConcurrentHashMap<>();
    private final Map<String, ServiceInfo> onlineServices = new ConcurrentHashMap<>();

    private CloudBridge bridge;

    /**
     * @param server Proxy
     * @param logger Plugin-Logger
     */
    @Inject
    public VelocityBridgePlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    /**
     * @param event Proxy-Start
     */
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        BridgeSettings settings;
        try {
            settings = BridgeSettings.fromEnvironment();
        } catch (BridgeConfigurationException e) {
            logger.error("ByteStorm-Bridge nicht konfiguriert: {}", e.getMessage());
            return;
        }

        try {
            bridge = CloudBridge.start(settings, this);
            logger.info("ByteStorm-Bridge verbunden als {}", settings.serviceName());
        } catch (BridgeException e) {
            logger.error("ByteStorm-Bridge konnte nicht starten: {}", e.getMessage());
        }
        removePlaceholderServer();
    }

    /**
     * Entfernt den Platzhalter-Server, den die Cloud ins Template schreibt, damit
     * Velocity überhaupt startet (§14). Danach kommen die Server allein aus der
     * Registry; statisch konfigurierte Server des Betreibers bleiben stehen.
     */
    private void removePlaceholderServer() {
        server.getServer(ProxyDefaults.PLACEHOLDER_SERVER).ifPresent(registered -> {
            server.unregisterServer(registered.getServerInfo());
            logger.info("Platzhalter-Server '{}' entfernt — Server kommen aus der Registry",
                    ProxyDefaults.PLACEHOLDER_SERVER);
        });
    }

    /**
     * @param event Proxy-Stop
     */
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
    }

    @Override
    public String platformName() {
        return "velocity";
    }

    @Override
    public String platformVersion() {
        return server.getVersion().getVersion();
    }

    @Override
    public int onlinePlayers() {
        return server.getPlayerCount();
    }

    @Override
    public int maxPlayers() {
        return server.getConfiguration().getShowMaxPlayers();
    }

    @Override
    public String motd() {
        return PlainTextComponentSerializer.plainText()
                .serialize(server.getConfiguration().getMotd());
    }

    @Override
    public void executeCommand(String command) {
        server.getCommandManager().executeAsync(server.getConsoleCommandSource(), command);
    }

    @Override
    public void shutdown() {
        server.shutdown();
    }

    @Override
    public void connectPlayer(String player, String target) {
        server.getPlayer(player).ifPresentOrElse(connected -> server.getServer(target)
                .ifPresentOrElse(registered -> {
                    // Verbindungsaufbau läuft asynchron; der Aufruf kommt aus einem Redisson-Thread.
                    connected.createConnectionRequest(registered).connect();
                    logger.info("Spieler '{}' wird nach '{}' geschickt", player, target);
                }, () -> logger.warn("Unbekannter Service '{}' — '{}' bleibt auf seinem Server",
                        target, player)),
                () -> logger.warn("Spieler '{}' ist nicht verbunden", player));
    }

    @Override
    public void kickPlayer(String player, String reason) {
        server.getPlayer(player).ifPresentOrElse(connected -> {
            connected.disconnect(Component.text(reason.isBlank()
                    ? BridgeText.DEFAULT_KICK_REASON : reason));
            logger.info("Spieler '{}' getrennt", player);
        }, () -> logger.warn("Spieler '{}' ist nicht verbunden", player));
    }

    /**
     * Meldet den Beitritt an die Cloud.
     *
     * @param event Login
     */
    @Subscribe
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
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (bridge == null || !bridge.isRunning()) {
            return;
        }
        bridge.playerLeft(playerInfo(event.getPlayer()));
    }

    private PlayerInfo playerInfo(Player player) {
        return PlayerInfo.newBuilder()
                .setUuid(player.getUniqueId().toString())
                .setName(player.getUsername())
                .setProxy(bridge.settings().serviceName())
                .setServer(player.getCurrentServer()
                        .map(connection -> connection.getServerInfo().getName())
                        .orElse(""))
                .build();
    }

    /**
     * Wählt beim Login den initialen Server aus der Registry (§14).
     *
     * <p>Velocity füllt die Auswahl aus {@code try} bzw. den Forced Hosts. Nur wenn
     * dort nichts steht oder der Platzhalter der Cloud übrig ist, übernimmt der
     * bevorzugte Startserver. Eine bewusst konfigurierte Auswahl des Betreibers
     * bleibt damit bestehen; ohne markiertes Fallback-Ziel kommt jeder erreichbare
     * Server in Frage, sonst könnte niemand beitreten.
     *
     * @param event Auswahl des initialen Servers
     */
    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        boolean placeholder = event.getInitialServer()
                .map(initial -> initial.getServerInfo().getName()
                        .equals(ProxyDefaults.PLACEHOLDER_SERVER))
                .orElse(true);
        if (!placeholder) {
            return;
        }
        FallbackSelector.selectInitial(onlineServices.values())
                .flatMap(service -> server.getServer(service.getName()))
                .ifPresentOrElse(event::setInitialServer, () -> logger.warn(
                        "Kein Startserver verfügbar — kein Server ist ONLINE"));
    }

    /**
     * Leitet einen Spieler nach einem Kick auf den nächsten Fallback-Server um.
     *
     * @param event Kick vom aktuellen Server
     */
    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        if (event.kickedDuringServerConnect()
                || !(event.getResult() instanceof KickedFromServerEvent.DisconnectPlayer)) {
            // Eine bereits gesetzte Umleitung eines anderen Plugins bleibt bestehen.
            return;
        }
        String kicked = event.getServer().getServerInfo().getName();
        InetSocketAddress kickedAddress = event.getServer().getServerInfo().getAddress();
        FallbackSelector.select(onlineServices.values(), kicked)
                // Derselbe Server kann unter zwei Namen laufen (alter Eintrag in velocity.toml
                // plus Registry-Name). Eine Umleitung auf die andere Adresse desselben Ports
                // würde ihn sofort wieder kicken — und die beiden Namen endlos hin- und
                // herschicken. Deshalb zählt die Adresse, nicht der Name.
                .filter(service -> !sameAddress(service, kickedAddress))
                .flatMap(service -> server.getServer(service.getName()))
                .ifPresent(target -> event.setResult(
                        KickedFromServerEvent.RedirectPlayer.create(target)));
    }

    /**
     * @param service Service aus der Registry
     * @param address Adresse des Servers, von dem der Spieler kam
     * @return {@code true}, wenn beide auf denselben Prozess zeigen
     */
    private static boolean sameAddress(ServiceInfo service, InetSocketAddress address) {
        if (address == null || service.getHost().isBlank()) {
            return false;
        }
        String host = address.getAddress() == null
                ? address.getHostString()
                : address.getAddress().getHostAddress();
        return service.getPort() == address.getPort()
                && (service.getHost().equalsIgnoreCase(host)
                || service.getHost().equalsIgnoreCase(address.getHostString()));
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
        // Statisch konfigurierte Server bleiben unangetastet.
        if (registeredServers.containsKey(service.getName())
                || server.getServer(service.getName()).isPresent()) {
            return;
        }
        ServerInfo info = new ServerInfo(service.getName(),
                new InetSocketAddress(service.getHost(), service.getPort()));
        try {
            server.registerServer(info);
            registeredServers.put(service.getName(), info);
            onlineServices.put(service.getName(), service);
            logger.info("Service '{}' als Server {}:{} registriert",
                    service.getName(), service.getHost(), service.getPort());
        } catch (RuntimeException e) {
            logger.warn("Service '{}' konnte nicht registriert werden: {}",
                    service.getName(), e.toString());
        }
    }

    private synchronized void unregister(String name) {
        onlineServices.remove(name);
        ServerInfo info = registeredServers.remove(name);
        if (info == null) {
            return;
        }
        try {
            server.unregisterServer(info);
            logger.info("Service '{}' als Server entfernt", name);
        } catch (RuntimeException e) {
            logger.warn("Service '{}' konnte nicht entfernt werden: {}", name, e.toString());
        }
    }
}
