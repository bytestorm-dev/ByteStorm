package dev.bytestorm.bridge.common;

import dev.bytestorm.api.bridge.BridgeChannels;
import dev.bytestorm.codec.ProtobufCodec;
import dev.bytestorm.codec.TypeRegistry;
import dev.bytestorm.proto.BridgeHello;
import dev.bytestorm.proto.BridgeHelloAck;
import dev.bytestorm.proto.ExecuteCommand;
import dev.bytestorm.proto.Heartbeat;
import dev.bytestorm.proto.ProtoVersion;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceStateChanged;
import dev.bytestorm.proto.StopServiceRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudBridgeIntegrationTest {

    private RedissonClient cloud;
    private UUID serviceId;

    @BeforeAll
    void connect() {
        String address = redisAddress();
        assumeTrue(address != null, "BYTESTORM_TEST_REDIS nicht gesetzt — Bridge-Test übersprungen");
        serviceId = UUID.randomUUID();

        Config config = new Config();
        config.setCodec(new ProtobufCodec(TypeRegistry.byteStormDefaults()));
        config.useSingleServer()
                .setAddress(address)
                .setConnectTimeout(5_000)
                .setTimeout(3_000)
                .setRetryAttempts(1);
        if (redisPassword() != null) {
            config.setPassword(redisPassword());
        }
        cloud = Redisson.create(config);
    }

    @AfterAll
    void cleanup() {
        if (cloud != null) {
            cloud.getKeys().deleteByPattern("bs:*");
            cloud.shutdown();
        }
    }

    @Test
    void connectsSendsHeartbeatsAndReceivesCommands() throws Exception {
        CountDownLatch helloReceived = new CountDownLatch(1);
        AtomicReference<BridgeHello> hello = new AtomicReference<>();
        RTopic helloChannel = cloud.getTopic(BridgeChannels.HELLO);
        helloChannel.addListener(BridgeHello.class, (channel, message) -> {
            if (message.getServiceId().equals(serviceId.toString())) {
                hello.set(message);
                cloud.getTopic(BridgeChannels.ack(serviceId.toString())).publish(
                        BridgeHelloAck.newBuilder()
                                .setAccepted(true)
                                .setProtocolMajor(ProtoVersion.MAJOR)
                                .setProtocolMinor(ProtoVersion.MINOR)
                                .build());
                helloReceived.countDown();
            }
        });

        LinkedBlockingQueue<Heartbeat> heartbeats = new LinkedBlockingQueue<>();
        cloud.getTopic(BridgeChannels.HEARTBEAT).addListener(Heartbeat.class,
                (channel, heartbeat) -> heartbeats.add(heartbeat));

        ServiceInfo existing = ServiceInfo.newBuilder()
                .setId("existing-service")
                .setName("Lobby-9")
                .setGroup("Lobby")
                .setType(dev.bytestorm.proto.ServiceType.SERVER)
                .setState(ServiceState.ONLINE)
                .setHost("localhost")
                .setPort(30009)
                .build();
        cloud.getMapCache(BridgeChannels.SERVICES).put(existing.getId(), existing);

        TestPlatform platform = new TestPlatform();
        BridgeSettings settings = new BridgeSettings(serviceId, "Lobby-1",
                redisAddress(), redisPassword(),
                Duration.ofSeconds(10), Duration.ofMillis(250));

        try (CloudBridge bridge = CloudBridge.start(settings, platform)) {
            assertTrue(bridge.isRunning());
            assertTrue(helloReceived.await(10, TimeUnit.SECONDS), "Handshake nicht angekommen");
            assertEquals("paper", hello.get().getPlatform());
            assertEquals(ProtoVersion.MAJOR, hello.get().getProtocolMajor());
            assertTrue(platform.snapshot.await(10, TimeUnit.SECONDS), "Registry-Snapshot nicht geliefert");
            assertEquals("Lobby-9", platform.snapshotService.get().getName());
            assertEquals(ServiceState.ONLINE, platform.snapshotService.get().getState());

            Heartbeat online = heartbeats.poll(10, TimeUnit.SECONDS);
            assertNotNull(online, "kein Heartbeat angekommen");
            assertEquals(ServiceState.ONLINE, online.getState());
            assertEquals(3, online.getOnlinePlayers());
            assertEquals(20, online.getMaxPlayers());
            assertEquals("Willkommen", online.getMotd());

            cloud.getTopic(BridgeChannels.commands(serviceId.toString()))
                    .publish(ExecuteCommand.newBuilder()
                            .setService(serviceId.toString())
                            .setCommand("say hallo")
                            .build());
            assertTrue(platform.executed.await(10, TimeUnit.SECONDS), "Befehl nicht ausgeführt");
            assertEquals("say hallo", platform.lastCommand.get());

            cloud.getTopic(BridgeChannels.event(ServiceStateChanged.getDescriptor().getFullName()))
                    .publish(ServiceStateChanged.newBuilder()
                            .setService(ServiceInfo.newBuilder()
                                    .setId(serviceId.toString())
                                    .setName("Lobby-1")
                                    .setGroup("Lobby")
                                    .setState(ServiceState.STOPPING))
                            .setPrevious(ServiceState.ONLINE)
                            .build());
            assertTrue(platform.stateChanged.await(10, TimeUnit.SECONDS), "Zustandswechsel nicht empfangen");
            assertEquals(ServiceState.STOPPING, platform.lastState.get());

            cloud.getTopic(BridgeChannels.commands(serviceId.toString()))
                    .publish(StopServiceRequest.newBuilder()
                            .setService(serviceId.toString())
                            .setForce(false)
                            .build());
            assertTrue(platform.shutdownRequested.await(10, TimeUnit.SECONDS), "Stop nicht zugestellt");
        }

        Heartbeat stopped = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Heartbeat next = heartbeats.poll(500, TimeUnit.MILLISECONDS);
            if (next != null && next.getState() == ServiceState.STOPPED) {
                stopped = next;
                break;
            }
        }
        assertNotNull(stopped, "sauberes Schließen muss STOPPED senden");
    }

    private static String redisAddress() {
        String value = System.getenv("BYTESTORM_TEST_REDIS");
        if (value == null || value.isBlank()) {
            value = System.getProperty("bytestorm.test.redis");
        }
        return value == null || value.isBlank() ? null : value;
    }

    private static String redisPassword() {
        String value = System.getenv("BYTESTORM_TEST_REDIS_PASSWORD");
        if (value == null || value.isBlank()) {
            value = System.getProperty("bytestorm.test.redis.password");
        }
        return value == null || value.isBlank() ? null : value;
    }

    private static final class TestPlatform implements BridgePlatform {

        private final CountDownLatch executed = new CountDownLatch(1);
        private final CountDownLatch stateChanged = new CountDownLatch(1);
        private final CountDownLatch shutdownRequested = new CountDownLatch(1);
        private final CountDownLatch snapshot = new CountDownLatch(1);
        private final AtomicReference<String> lastCommand = new AtomicReference<>();
        private final AtomicReference<ServiceState> lastState = new AtomicReference<>();
        private final AtomicReference<ServiceInfo> snapshotService = new AtomicReference<>();

        @Override
        public String platformName() {
            return "paper";
        }

        @Override
        public String platformVersion() {
            return "1.21.8";
        }

        @Override
        public int onlinePlayers() {
            return 3;
        }

        @Override
        public int maxPlayers() {
            return 20;
        }

        @Override
        public String motd() {
            return "Willkommen";
        }

        @Override
        public void executeCommand(String command) {
            lastCommand.set(command);
            executed.countDown();
        }

        @Override
        public void shutdown() {
            shutdownRequested.countDown();
        }

        @Override
        public void onStateChanged(ServiceStateChanged event) {
            lastState.set(event.getService().getState());
            stateChanged.countDown();
        }

        @Override
        public void onSnapshot(java.util.Collection<ServiceInfo> services) {
            services.forEach(snapshotService::set);
            snapshot.countDown();
        }
    }
}
