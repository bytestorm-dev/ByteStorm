package dev.bytestorm.node.bridge;

import dev.bytestorm.api.bridge.BridgeChannels;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.redis.ServiceRegistryStore;
import dev.bytestorm.node.lifecycle.ServiceManager;
import dev.bytestorm.proto.BridgeHello;
import dev.bytestorm.proto.BridgeHelloAck;
import dev.bytestorm.proto.Heartbeat;
import dev.bytestorm.proto.ProtoVersion;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Nimmt Handshake und Heartbeats der Bridge-Plugins entgegen (§9.2, §15).
 *
 * <p>Der Registrar ist die einzige Stelle, an der ein Bridge-Signal den
 * Service-Zustand auf {@code ONLINE} setzt und den Eintrag in
 * {@code bs:services} mit einer TTL versieht. Damit bleibt die Cloud die
 * Quelle der Wahrheit; die Bridge sendet nur Nachrichten.
 *
 * <p>Ein Heartbeat mit {@code STOPPED} entfernt den Registry-Eintrag sofort,
 * statt die TTL abzuwarten. Unbekannte Service-IDs und inkompatible
 * Proto-Major-Versionen werden mit einer ablehnenden Antwort quittiert.
 */
public final class BridgeRegistrar implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BridgeRegistrar.class);

    private final RedissonClient client;
    private final ServiceManager services;
    private final ServiceRegistryStore registry;
    private final Duration heartbeatTtl;

    private RTopic helloTopic;
    private RTopic heartbeatTopic;
    private int helloListener = -1;
    private int heartbeatListener = -1;

    /**
     * @param client       verbundener Redisson-Client
     * @param services     Service-Manager des Nodes
     * @param registry     Live-Registry in Redis
     */
    public BridgeRegistrar(RedissonClient client, ServiceManager services, ServiceRegistryStore registry) {
        this(client, services, registry, ServiceManager.DEFAULT_HEARTBEAT_TIMEOUT);
    }

    /**
     * @param client       verbundener Redisson-Client
     * @param services     Service-Manager des Nodes
     * @param registry     Live-Registry in Redis
     * @param heartbeatTtl Gültigkeit eines Registry-Eintrags ohne Heartbeat
     */
    public BridgeRegistrar(RedissonClient client, ServiceManager services,
                           ServiceRegistryStore registry, Duration heartbeatTtl) {
        this.client = Objects.requireNonNull(client, "client");
        this.services = Objects.requireNonNull(services, "services");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.heartbeatTtl = Objects.requireNonNull(heartbeatTtl, "heartbeatTtl");
        if (heartbeatTtl.isZero() || heartbeatTtl.isNegative()) {
            throw new IllegalArgumentException("heartbeatTtl muss positiv sein: " + heartbeatTtl);
        }
    }

    /**
     * Abonniert Handshake- und Heartbeat-Topic. Mehrfache Aufrufe sind wirkungslos.
     */
    public synchronized void start() {
        if (helloListener >= 0) {
            return;
        }
        helloTopic = client.getTopic(BridgeChannels.HELLO);
        heartbeatTopic = client.getTopic(BridgeChannels.HEARTBEAT);
        helloListener = helloTopic.addListener(BridgeHello.class, (channel, hello) -> onHello(hello));
        heartbeatListener = heartbeatTopic.addListener(Heartbeat.class,
                (channel, heartbeat) -> onHeartbeat(heartbeat));
        log.info("Bridge-Registrar bereit (TTL {} s)", heartbeatTtl.toSeconds());
    }

    /**
     * Entfernt die Abonnements.
     */
    @Override
    public synchronized void close() {
        removeListener(helloTopic, helloListener);
        removeListener(heartbeatTopic, heartbeatListener);
        helloListener = -1;
        heartbeatListener = -1;
    }

    private void onHello(BridgeHello hello) {
        Optional<ServiceId> serviceId = parse(hello.getServiceId());
        if (serviceId.isEmpty()) {
            log.warn("Bridge-Handshake mit ungültiger Service-ID: '{}'", hello.getServiceId());
            return;
        }

        String rejection = null;
        if (!ProtoVersion.isCompatible(hello.getProtocolMajor())) {
            rejection = "Protokoll-Major " + hello.getProtocolMajor()
                    + " ist inkompatibel (Cloud: " + ProtoVersion.MAJOR + ")";
        } else if (services.info(serviceId.get()).isEmpty()) {
            rejection = "Service " + hello.getServiceId() + " ist nicht registriert";
        }

        if (rejection != null) {
            log.warn("Bridge-Handshake für '{}' abgelehnt: {}", hello.getServiceId(), rejection);
            ack(serviceId.get(), false, rejection);
            return;
        }

        try {
            services.online(serviceId.get());
            publishRegistry(serviceId.get(), null);
            ack(serviceId.get(), true, "");
            log.info("Bridge für '{}' verbunden ({} {})", hello.getServiceId(),
                    hello.getPlatform(), hello.getPlatformVersion());
        } catch (RuntimeException e) {
            log.warn("Bridge-Handshake für '{}' fehlgeschlagen: {}", hello.getServiceId(), e.toString());
            ack(serviceId.get(), false, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void onHeartbeat(Heartbeat heartbeat) {
        Optional<ServiceId> serviceId = parse(heartbeat.getServiceId());
        if (serviceId.isEmpty()) {
            log.warn("Bridge-Heartbeat mit ungültiger Service-ID: '{}'", heartbeat.getServiceId());
            return;
        }

        if (heartbeat.getState() == ServiceState.STOPPED) {
            registry.remove(heartbeat.getServiceId());
            return;
        }

        try {
            services.heartbeat(serviceId.get());
            if (heartbeat.getState() == ServiceState.ONLINE) {
                Optional<ServiceInfo> info = services.info(serviceId.get());
                if (info.isPresent() && info.get().getState() == ServiceState.STARTING) {
                    services.online(serviceId.get());
                }
            }
            publishRegistry(serviceId.get(), heartbeat);
        } catch (IllegalArgumentException e) {
            log.warn("Bridge-Heartbeat für unbekannten Service '{}'", heartbeat.getServiceId());
        } catch (RuntimeException e) {
            log.warn("Bridge-Heartbeat für '{}' nicht verarbeitet: {}",
                    heartbeat.getServiceId(), e.toString());
        }
    }

    private void publishRegistry(ServiceId serviceId, Heartbeat heartbeat) {
        Optional<ServiceInfo> current = services.info(serviceId);
        if (current.isEmpty()) {
            return;
        }
        ServiceInfo info = current.get();
        if (heartbeat != null) {
            info = info.toBuilder()
                    .setOnlinePlayers(heartbeat.getOnlinePlayers())
                    .setMaxPlayers(heartbeat.getMaxPlayers())
                    .setState(heartbeat.getState())
                    .build();
        }
        registry.heartbeat(info, heartbeatTtl);
    }

    private void ack(ServiceId serviceId, boolean accepted, String reason) {
        BridgeHelloAck ack = BridgeHelloAck.newBuilder()
                .setAccepted(accepted)
                .setProtocolMajor(ProtoVersion.MAJOR)
                .setProtocolMinor(ProtoVersion.MINOR)
                .setReason(reason == null ? "" : reason)
                .build();
        try {
            client.getTopic(BridgeChannels.ack(serviceId.toString())).publish(ack);
        } catch (RuntimeException e) {
            log.warn("Handshake-Antwort für '{}' nicht gesendet: {}", serviceId, e.toString());
        }
    }

    private static Optional<ServiceId> parse(String raw) {
        try {
            return Optional.of(ServiceId.parse(raw));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private static void removeListener(RTopic topic, int listenerId) {
        if (topic == null || listenerId < 0) {
            return;
        }
        try {
            topic.removeListener(listenerId);
        } catch (RuntimeException e) {
            log.debug("Bridge-Registrar-Listener {} konnte nicht entfernt werden: {}",
                    listenerId, e.toString());
        }
    }
}
