package dev.bytestorm.plugins.lobby.redis;

import dev.bytestorm.api.bridge.BridgeChannels;
import dev.bytestorm.codec.ProtobufCodec;
import dev.bytestorm.codec.TypeRegistry;
import dev.bytestorm.plugins.lobby.config.LobbySettings;
import dev.bytestorm.proto.ConnectPlayerRequest;
import dev.bytestorm.proto.PlayerInfo;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceType;
import org.jetbrains.annotations.Nullable;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Der Live-Zustand der Cloud, gelesen aus Redis (§7, §15).
 *
 * <p>Das Plugin steigt nicht in den Handshake der Bridge ein: Es öffnet eine
 * eigene, nur lesende Verbindung auf dieselben Schlüssel. Damit funktioniert es
 * auch auf einem Lobby-Server, der (noch) keine Bridge hat, und es kann der
 * Bridge nicht in die Quere kommen.
 *
 * <p>Ein Wechsel läuft über den Kanal, den auch die Cloud benutzt:
 * {@code bs:bridge:<proxy-id>:commands} mit einer {@link ConnectPlayerRequest}.
 * Den Proxy findet das Plugin über {@code bs:players} — dort steht, über welchen
 * Proxy der Spieler verbunden ist. Die Cloud erfährt den neuen Stand wie bei
 * jedem Wechsel über die {@code PlayerJoined}-Meldung des Proxys.
 *
 * <p>Alle Aufrufe blockieren; sie gehören in asynchrone Tasks.
 */
public final class LiveState implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger("ByteStormLobby");

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int COMMAND_TIMEOUT_MS = 3_000;
    private static final int RETRY_ATTEMPTS = 3;

    private final RedissonClient client;
    private final RMapCache<String, ServiceInfo> services;
    private final RMap<String, PlayerInfo> players;

    private LiveState(RedissonClient client) {
        this.client = client;
        this.services = client.getMapCache(BridgeChannels.SERVICES);
        this.players = client.getMap("bs:players");
    }

    /**
     * @param settings geprüfte Einstellungen
     * @return verbundener Lesezugriff
     */
    public static LiveState connect(LobbySettings settings) {
        Config config = new Config();
        // Derselbe Codec wie Cloud und Bridge, sonst sind die Einträge unlesbar.
        config.setCodec(new ProtobufCodec(TypeRegistry.byteStormDefaults()));

        SingleServerConfig server = config.useSingleServer()
                .setAddress(settings.redisAddress())
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setTimeout(COMMAND_TIMEOUT_MS)
                .setRetryAttempts(RETRY_ATTEMPTS)
                .setClientName("bytestorm-lobby");
        if (settings.redisPassword() != null) {
            config.setPassword(settings.redisPassword());
        }
        return new LiveState(Redisson.create(config));
    }

    /**
     * @return alle Services aus der Registry, auch gestoppte bis zum TTL-Ablauf
     */
    public List<ServiceInfo> services() {
        return List.copyOf(services.readAllValues());
    }

    /**
     * @param uuid Spieler-UUID
     * @return der Spieler, falls die Cloud ihn kennt
     */
    public Optional<PlayerInfo> player(UUID uuid) {
        return Optional.ofNullable(players.get(uuid.toString()));
    }

    /**
     * Schickt einen Spieler über seinen Proxy auf einen anderen Service.
     *
     * @param uuid   Spieler-UUID
     * @param name   Spielername, wie ihn der Proxy kennt
     * @param target Service-Name des Ziels
     * @return {@code false}, wenn Spieler oder Proxy nicht auffindbar sind
     */
    public boolean requestSwitch(UUID uuid, String name, String target) {
        PlayerInfo player = players.get(uuid.toString());
        if (player == null) {
            LOGGER.warning("Spieler '" + name + "' steht nicht in bs:players — kein Wechsel");
            return false;
        }

        ServiceInfo proxy = proxy(player.getProxy());
        if (proxy == null) {
            LOGGER.warning("Proxy '" + player.getProxy() + "' von '" + name
                    + "' ist nicht ONLINE — kein Wechsel");
            return false;
        }

        ConnectPlayerRequest request = ConnectPlayerRequest.newBuilder()
                .setService(proxy.getId())
                .setPlayer(name)
                .setTarget(target)
                .build();
        client.getTopic(BridgeChannels.commands(proxy.getId())).publish(request);
        return true;
    }

    /**
     * @param name Service-Name des Proxys
     * @return der Proxy aus der Registry, {@code null} wenn keiner passt
     */
    private @Nullable ServiceInfo proxy(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (ServiceInfo service : services.readAllValues()) {
            if (service.getType() == ServiceType.PROXY && service.getName().equalsIgnoreCase(name)) {
                return service;
            }
        }
        return null;
    }

    @Override
    public void close() {
        try {
            client.shutdown();
        } catch (RuntimeException e) {
            LOGGER.fine("Redis-Verbindung des Lobby-Plugins war bereits geschlossen: " + e);
        }
    }
}
