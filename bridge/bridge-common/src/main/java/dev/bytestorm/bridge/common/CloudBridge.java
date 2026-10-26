package dev.bytestorm.bridge.common;

import dev.bytestorm.api.bridge.BridgeChannels;
import dev.bytestorm.codec.ProtobufCodec;
import dev.bytestorm.codec.TypeRegistry;
import dev.bytestorm.proto.BridgeHello;
import dev.bytestorm.proto.BridgeHelloAck;
import dev.bytestorm.proto.ConnectPlayerRequest;
import dev.bytestorm.proto.ExecuteCommand;
import dev.bytestorm.proto.Heartbeat;
import dev.bytestorm.proto.KickPlayerRequest;
import dev.bytestorm.proto.ProtoVersion;
import dev.bytestorm.proto.PlayerInfo;
import dev.bytestorm.proto.PlayerJoined;
import dev.bytestorm.proto.PlayerLeft;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceStateChanged;
import dev.bytestorm.proto.StopServiceRequest;
import org.redisson.Redisson;
import org.redisson.api.RMapCache;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verbindung eines Service-Plugins zur Cloud (§15).
 *
 * <p>Ablauf: Redisson verbinden → {@code BridgeHello} senden und auf
 * {@code BridgeHelloAck} warten → Befehls- und Zustands-Topics abonnieren →
 * regelmäßig {@code Heartbeat} senden. Die Cloud setzt den Service erst nach
 * einem akzeptierten Handshake auf {@code ONLINE}; Heartbeats halten den
 * Redis-Eintrag am Leben.
 *
 * <p>Die Bridge sendet nur Nachrichten, sie schreibt den Service-Eintrag nicht
 * selbst. Ein sauberes {@link #close()} sendet einen letzten Heartbeat mit
 * {@code STOPPED}, damit die Cloud den Eintrag entfernen kann. Redisson
 * verbindet sich nach einem Netzausfall selbst neu; Topics werden dabei erneut
 * abonniert, ohne einen zweiten Service zu registrieren.
 */
public final class CloudBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CloudBridge.class);

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int COMMAND_TIMEOUT_MS = 3_000;
    private static final int RETRY_ATTEMPTS = 3;

    private final BridgeSettings settings;
    private final BridgePlatform platform;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean accepted = new AtomicBoolean();

    private volatile RedissonClient client;
    private ScheduledExecutorService heartbeats;
    private Thread shutdownHook;

    private RTopic ackTopic;
    private RTopic commandTopic;
    private RTopic stateTopic;
    private int ackListener = -1;
    private int commandListener = -1;
    private int stopListener = -1;
    private int connectListener = -1;
    private int kickListener = -1;
    private int stateListener = -1;

    private CloudBridge(BridgeSettings settings, BridgePlatform platform) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    /**
     * Baut die Verbindung auf und führt den Handshake aus.
     *
     * @param settings Zugangsdaten und Zeitfenster
     * @param platform Plattform-Adapter
     * @return verbundene Bridge
     * @throws BridgeConfigurationException wenn die Cloud nicht erreichbar ist
     * @throws BridgeRejectedException      wenn die Cloud den Handshake ablehnt
     */
    public static CloudBridge start(BridgeSettings settings, BridgePlatform platform) {
        CloudBridge bridge = new CloudBridge(settings, platform);
        try {
            bridge.connect();
            return bridge;
        } catch (RuntimeException e) {
            bridge.close();
            throw e;
        }
    }

    /**
     * @return {@code true}, solange die Bridge verbunden und nicht geschlossen ist
     */
    public boolean isRunning() {
        return accepted.get() && !closed.get();
    }

    /**
     * @return die verwendeten Einstellungen (Passwort redigiert)
     */
    public BridgeSettings settings() {
        return settings;
    }

    /**
     * Meldet einen beigetretenen Spieler an die Cloud.
     *
     * @param player Spieler mit UUID, Name, Proxy und aktuellem Server
     */
    public void playerJoined(PlayerInfo player) {
        if (!isRunning()) {
            return;
        }
        publish(BridgeChannels.event(PlayerJoined.getDescriptor().getFullName()),
                PlayerJoined.newBuilder().setPlayer(player).build());
    }

    /**
     * Meldet einen verlassenen Spieler an die Cloud.
     *
     * @param player Spieler mit UUID, Name, Proxy und letztem Server
     */
    public void playerLeft(PlayerInfo player) {
        if (!isRunning()) {
            return;
        }
        publish(BridgeChannels.event(PlayerLeft.getDescriptor().getFullName()),
                PlayerLeft.newBuilder().setPlayer(player).build());
    }

    private void publish(String topic, com.google.protobuf.Message message) {
        try {
            client.getTopic(topic).publish(message);
        } catch (RuntimeException e) {
            log.warn("Event für '{}' nicht gesendet: {}", settings.serviceName(), e.toString());
        }
    }

    private void connect() {
        client = createClient();
        try {
            handshake();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BridgeException("Handshake wurde abgebrochen", e);
        }

        subscribeCommands();
        subscribeStateChanges();
        deliverSnapshot();
        startHeartbeats();
        installShutdownHook();
        log.info("Bridge für '{}' verbunden ({} {}, Proto {}.{})",
                settings.serviceName(), platform.platformName(), platform.platformVersion(),
                ProtoVersion.MAJOR, ProtoVersion.MINOR);
    }

    private RedissonClient createClient() {
        Config config = new Config();
        config.setCodec(new ProtobufCodec(TypeRegistry.byteStormDefaults()));
        SingleServerConfig server = config.useSingleServer()
                .setAddress(settings.redisAddress())
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setTimeout(COMMAND_TIMEOUT_MS)
                .setRetryAttempts(RETRY_ATTEMPTS)
                .setClientName("bytestorm-bridge-" + settings.serviceName());
        if (settings.redisPassword() != null) {
            // Redisson 4.x: Passwort am Top-Level-Config setzen.
            config.setPassword(settings.redisPassword());
        }
        try {
            return Redisson.create(config);
        } catch (RedisConnectionException e) {
            throw new BridgeException("Redis nicht erreichbar unter " + settings.redisAddress(), e);
        }
    }

    private void handshake() throws InterruptedException {
        ackTopic = client.getTopic(BridgeChannels.ack(settings.serviceIdAsString()));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<BridgeHelloAck> response = new AtomicReference<>();

        ackListener = ackTopic.addListener(BridgeHelloAck.class, (channel, ack) -> {
            if (response.compareAndSet(null, ack)) {
                latch.countDown();
            }
        });

        BridgeHello hello = BridgeHello.newBuilder()
                .setProtocolMajor(ProtoVersion.MAJOR)
                .setProtocolMinor(ProtoVersion.MINOR)
                .setServiceId(settings.serviceIdAsString())
                .setPlatform(platform.platformName())
                .setPlatformVersion(platform.platformVersion())
                .build();

        RTopic helloTopic = client.getTopic(BridgeChannels.HELLO);
        long deadline = System.nanoTime() + settings.handshakeTimeout().toNanos();
        while (System.nanoTime() < deadline && latch.getCount() > 0) {
            try {
                helloTopic.publish(hello);
            } catch (RuntimeException e) {
                log.warn("Handshake für '{}' konnte nicht gesendet werden: {}",
                        settings.serviceName(), e.toString());
            }
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis <= 0) {
                break;
            }
            // Kurz warten, dann erneut anfragen: die Cloud kann etwas später bereit sein.
            latch.await(Math.min(2_000, remainingMillis), TimeUnit.MILLISECONDS);
        }

        removeListener(ackTopic, ackListener);
        ackListener = -1;

        BridgeHelloAck ack = response.get();
        if (ack == null) {
            throw new BridgeException("Cloud antwortet nicht auf den Handshake für '"
                    + settings.serviceName() + "' innerhalb von " + settings.handshakeTimeout());
        }
        if (!ack.getAccepted()) {
            throw new BridgeRejectedException("Cloud hat die Bridge für '"
                    + settings.serviceName() + "' abgelehnt: " + ack.getReason());
        }
        if (ack.getProtocolMajor() != ProtoVersion.MAJOR) {
            throw new BridgeRejectedException("Cloud spricht Proto-Major " + ack.getProtocolMajor()
                    + ", das Plugin " + ProtoVersion.MAJOR);
        }
        accepted.set(true);
    }

    private void subscribeCommands() {
        commandTopic = client.getTopic(BridgeChannels.commands(settings.serviceIdAsString()));
        commandListener = commandTopic.addListener(ExecuteCommand.class, (channel, command) -> {
            if (!command.getService().equals(settings.serviceIdAsString())) {
                return;
            }
            try {
                platform.executeCommand(command.getCommand());
            } catch (RuntimeException e) {
                log.warn("Befehl für '{}' fehlgeschlagen: {}", settings.serviceName(), e.toString());
            }
        });
        stopListener = commandTopic.addListener(StopServiceRequest.class, (channel, request) -> {
            if (!request.getService().equals(settings.serviceIdAsString())) {
                return;
            }
            log.info("Cloud stoppt '{}'{}", settings.serviceName(),
                    request.getForce() ? " (erzwungen)" : "");
            try {
                platform.shutdown();
            } catch (RuntimeException e) {
                log.warn("Stop von '{}' fehlgeschlagen: {}", settings.serviceName(), e.toString());
            }
        });
        // Spieler-Aktionen laufen nur über Proxies; ein Backend lehnt sie ab.
        connectListener = commandTopic.addListener(ConnectPlayerRequest.class, (channel, request) -> {
            if (!request.getService().equals(settings.serviceIdAsString())) {
                return;
            }
            log.info("Cloud schickt '{}' nach '{}'", request.getPlayer(), request.getTarget());
            try {
                platform.connectPlayer(request.getPlayer(), request.getTarget());
            } catch (RuntimeException e) {
                log.warn("Spieler '{}' konnte nicht nach '{}' geschickt werden: {}",
                        request.getPlayer(), request.getTarget(), e.toString());
            }
        });
        kickListener = commandTopic.addListener(KickPlayerRequest.class, (channel, request) -> {
            if (!request.getService().equals(settings.serviceIdAsString())) {
                return;
            }
            log.info("Cloud trennt '{}'{}", request.getPlayer(),
                    request.getReason().isBlank() ? "" : " (" + request.getReason() + ")");
            try {
                platform.kickPlayer(request.getPlayer(), request.getReason());
            } catch (RuntimeException e) {
                log.warn("Spieler '{}' konnte nicht getrennt werden: {}",
                        request.getPlayer(), e.toString());
            }
        });
    }

    private void subscribeStateChanges() {
        stateTopic = client.getTopic(BridgeChannels.event(
                ServiceStateChanged.getDescriptor().getFullName()));
        // Alle Zustandswechsel, nicht nur die des eigenen Services: ein Proxy
        // registriert daraus die Server, ein Service kann auf den eigenen Stop reagieren.
        stateListener = stateTopic.addListener(ServiceStateChanged.class, (channel, event) -> {
            try {
                platform.onStateChanged(event);
            } catch (RuntimeException e) {
                log.warn("Zustandswechsel von '{}' konnte nicht verarbeitet werden: {}",
                        settings.serviceName(), e.toString());
            }
        });
    }

    /**
     * Liest die bereits laufenden Services aus der Registry. Der Aufruf kommt
     * nach den Abonnements, damit zwischen Snapshot und Events kein Start
     * verloren geht; doppelte Einträge sind für die Plattform idempotent.
     */
    private void deliverSnapshot() {
        try {
            RMapCache<String, ServiceInfo> services = client.getMapCache(BridgeChannels.SERVICES);
            List<ServiceInfo> snapshot = List.copyOf(services.readAllValues());
            if (!snapshot.isEmpty()) {
                platform.onSnapshot(snapshot);
                log.info("{} Services aus der Registry übernommen", snapshot.size());
            }
        } catch (RuntimeException e) {
            log.warn("Registry-Snapshot für '{}' nicht gelesen: {}", settings.serviceName(), e.toString());
        }
    }

    private void startHeartbeats() {
        heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bytestorm-bridge-heartbeat-" + settings.serviceName());
            thread.setDaemon(true);
            return thread;
        });
        long interval = settings.heartbeatInterval().toMillis();
        heartbeats.scheduleWithFixedDelay(() -> {
            if (!closed.get()) {
                sendHeartbeat(ServiceState.ONLINE);
            }
        }, 0, interval, TimeUnit.MILLISECONDS);
    }

    private synchronized void sendHeartbeat(ServiceState state) {
        try {
            String motd = platform.motd();
            Heartbeat heartbeat = Heartbeat.newBuilder()
                    .setServiceId(settings.serviceIdAsString())
                    .setState(state)
                    .setOnlinePlayers(Math.max(0, platform.onlinePlayers()))
                    .setMaxPlayers(Math.max(0, platform.maxPlayers()))
                    .setMotd(motd == null ? "" : motd)
                    .setTime(System.currentTimeMillis())
                    .build();
            client.getTopic(BridgeChannels.HEARTBEAT).publish(heartbeat);
        } catch (RuntimeException e) {
            // Ein einzelner Fehlschlag darf den Service nicht beenden; Redisson
            // versucht die Verbindung selbst wieder aufzubauen.
            log.warn("Heartbeat für '{}' nicht gesendet: {}", settings.serviceName(), e.toString());
        }
    }

    private void installShutdownHook() {
        shutdownHook = new Thread(this::close, "bytestorm-bridge-shutdown-" + settings.serviceName());
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        if (heartbeats != null) {
            heartbeats.shutdownNow();
        }
        if (client != null && accepted.get()) {
            // Letzter Zustand: die Cloud entfernt den Eintrag, statt auf die TTL zu warten.
            sendHeartbeat(ServiceState.STOPPED);
        }
        removeListeners();
        if (client != null) {
            try {
                client.shutdown();
            } catch (RuntimeException e) {
                log.debug("Redis-Verbindung der Bridge war bereits geschlossen: {}", e.toString());
            }
        }
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // Der Prozess befindet sich bereits im Shutdown.
            }
        }
        accepted.set(false);
    }

    private void removeListeners() {
        removeListener(ackTopic, ackListener);
        removeListener(commandTopic, commandListener);
        removeListener(commandTopic, stopListener);
        removeListener(commandTopic, connectListener);
        removeListener(commandTopic, kickListener);
        removeListener(stateTopic, stateListener);
        ackListener = -1;
        commandListener = -1;
        stopListener = -1;
        connectListener = -1;
        kickListener = -1;
        stateListener = -1;
    }

    private static void removeListener(RTopic topic, int listenerId) {
        if (topic == null || listenerId < 0) {
            return;
        }
        try {
            topic.removeListener(listenerId);
        } catch (RuntimeException e) {
            log.debug("Bridge-Listener {} konnte nicht entfernt werden: {}", listenerId, e.toString());
        }
    }

}
