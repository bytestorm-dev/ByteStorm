package dev.bytestorm.node.bridge;

import dev.bytestorm.api.bridge.BridgeChannels;
import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.api.service.ServiceHandle;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.codec.ProtobufCodec;
import dev.bytestorm.codec.TypeRegistry;
import dev.bytestorm.core.config.CrashSettings;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.core.redis.RedisProvider;
import dev.bytestorm.core.redis.ServiceRegistryStore;
import dev.bytestorm.node.crash.CrashReportWriter;
import dev.bytestorm.node.crash.CrashSupport;
import dev.bytestorm.node.lifecycle.ServiceDefinition;
import dev.bytestorm.node.lifecycle.ServiceManager;
import dev.bytestorm.node.runtime.PortLease;
import dev.bytestorm.node.runtime.ServiceLaunch;
import dev.bytestorm.node.runtime.ServiceRuntime;
import dev.bytestorm.proto.BridgeHello;
import dev.bytestorm.proto.BridgeHelloAck;
import dev.bytestorm.proto.Heartbeat;
import dev.bytestorm.proto.ProtoVersion;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.api.RTopic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BridgeRegistrarIntegrationTest {

    private Path temp;
    private RedisProvider provider;
    private ServiceRegistryStore registry;
    private ServiceManager manager;
    private BridgeRegistrar registrar;
    private ServiceDefinition definition;

    @BeforeAll
    void connect() throws Exception {
        String address = redisAddress();
        assumeTrue(address != null, "BYTESTORM_TEST_REDIS nicht gesetzt — Registrar-Test übersprungen");
        temp = Files.createTempDirectory("bytestorm-bridge-");

        provider = RedisProvider.connect(new RedisSettings(address, redisPassword()),
                new ProtobufCodec(TypeRegistry.byteStormDefaults()));
        registry = new ServiceRegistryStore(provider.client());

        manager = new ServiceManager(
                group -> new FakeRuntime(),
                (group, name) -> PortLease.fixed(30000),
                service -> {
                },
                event -> {
                },
                new CrashSupport(new CrashSettings(1, List.of(0)),
                        new CrashReportWriter(temp.resolve("crash-reports")),
                        event -> {
                        },
                        (task, delay) -> {
                        },
                        group -> {
                        }));

        definition = definition("Lobby-1");
        manager.start(definition);

        registrar = new BridgeRegistrar(provider.client(), manager, registry);
        registrar.start();
    }

    @AfterAll
    void cleanup() {
        if (registrar != null) {
            registrar.close();
        }
        if (manager != null) {
            manager.close();
        }
        if (provider != null) {
            provider.client().getKeys().deleteByPattern("bs:*");
            provider.close();
        }
        deleteRecursively(temp);
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var files = Files.walk(path)) {
            files.sorted(Comparator.reverseOrder()).forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // Testaufräumen darf fehlschlagen, ohne den Test zu kippen.
                }
            });
        } catch (IOException ignored) {
            // Testaufräumen darf fehlschlagen, ohne den Test zu kippen.
        }
    }

    @Test
    void acceptedHandshakeBringsServiceOnlineAndWritesRegistry() throws Exception {
        BridgeHelloAck ack = handshake(definition.id(), ProtoVersion.MAJOR);

        assertTrue(ack.getAccepted(), ack.getReason());
        assertEquals(ProtoVersion.MAJOR, ack.getProtocolMajor());
        assertEquals(ServiceState.ONLINE, manager.info(definition.id()).orElseThrow().getState());

        ServiceInfo stored = awaitRegistry(definition.id());
        assertEquals(ServiceState.ONLINE, stored.getState());
        assertEquals("Lobby-1", stored.getName());
    }

    @Test
    void heartbeatUpdatesPlayersAndStopRemovesTheEntry() throws Exception {
        handshake(definition.id(), ProtoVersion.MAJOR);
        awaitRegistry(definition.id());

        provider.client().getTopic(BridgeChannels.HEARTBEAT).publish(Heartbeat.newBuilder()
                .setServiceId(definition.id().toString())
                .setState(ServiceState.ONLINE)
                .setOnlinePlayers(7)
                .setMaxPlayers(100)
                .setMotd("Hallo")
                .setTime(System.currentTimeMillis())
                .build());

        ServiceInfo updated = awaitRegistryWithPlayers(definition.id(), 7);
        assertEquals(100, updated.getMaxPlayers());

        provider.client().getTopic(BridgeChannels.HEARTBEAT).publish(Heartbeat.newBuilder()
                .setServiceId(definition.id().toString())
                .setState(ServiceState.STOPPED)
                .setTime(System.currentTimeMillis())
                .build());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && registry.get(definition.id().toString()).isPresent()) {
            Thread.sleep(50);
        }
        assertTrue(registry.get(definition.id().toString()).isEmpty());
    }

    @Test
    void incompatibleMajorIsRejected() throws Exception {
        ServiceDefinition incompatible = definition("Lobby-2");
        manager.start(incompatible);

        BridgeHelloAck ack = handshake(incompatible.id(), ProtoVersion.MAJOR + 1);

        assertFalse(ack.getAccepted());
        assertTrue(ack.getReason().contains("inkompatibel"), ack.getReason());
        assertEquals(ServiceState.STARTING, manager.info(incompatible.id()).orElseThrow().getState());
    }

    @Test
    void unknownServiceIsRejected() throws Exception {
        BridgeHelloAck ack = handshake(ServiceId.random(), ProtoVersion.MAJOR);

        assertFalse(ack.getAccepted());
        assertTrue(ack.getReason().contains("nicht registriert"), ack.getReason());
    }

    private BridgeHelloAck handshake(ServiceId id, int major) throws Exception {
        CompletableFuture<BridgeHelloAck> response = new CompletableFuture<>();
        RTopic ackTopic = provider.client().getTopic(BridgeChannels.ack(id.toString()));
        int listener = ackTopic.addListener(BridgeHelloAck.class, (channel, ack) -> response.complete(ack));
        try {
            provider.client().getTopic(BridgeChannels.HELLO).publish(BridgeHello.newBuilder()
                    .setProtocolMajor(major)
                    .setProtocolMinor(ProtoVersion.MINOR)
                    .setServiceId(id.toString())
                    .setPlatform("paper")
                    .setPlatformVersion("1.21.8")
                    .build());
            BridgeHelloAck ack = response.get(10, TimeUnit.SECONDS);
            assertNotNull(ack);
            return ack;
        } finally {
            ackTopic.removeListener(listener);
        }
    }

    private ServiceInfo awaitRegistry(ServiceId id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var info = registry.get(id.toString());
            if (info.isPresent()) {
                return info.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Service " + id + " nicht in der Registry");
    }

    private ServiceInfo awaitRegistryWithPlayers(ServiceId id, int players) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var info = registry.get(id.toString());
            if (info.isPresent() && info.get().getOnlinePlayers() == players) {
                return info.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Spielerzahl " + players + " nicht in der Registry");
    }

    private ServiceDefinition definition(String name) {
        ServiceGroup group = ServiceGroup.newBuilder()
                .setName("Lobby")
                .setMode(ServiceMode.DYNAMIC)
                .setMaxPlayers(100)
                .build();
        return new ServiceDefinition(ServiceId.random(), name, group, temp.resolve(name),
                temp.resolve("server.jar"), null,
                LaunchOptions.builder().memoryMb(512).runtime(RuntimeType.PROCESS).build(),
                new RedisSettings(redisAddress(), redisPassword()));
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

    private static final class FakeRuntime implements ServiceRuntime {

        @Override
        public ServiceHandle start(ServiceLaunch launch) {
            return new FakeHandle(launch);
        }

        @Override
        public void stop(ServiceHandle handle, Duration timeout) {
        }

        @Override
        public void kill(ServiceHandle handle) {
        }

        @Override
        public boolean isAlive(ServiceHandle handle) {
            return true;
        }

        @Override
        public List<String> logs(ServiceHandle handle) {
            return List.of();
        }
    }

    private static final class FakeHandle implements ServiceHandle {

        private final ServiceLaunch launch;

        private FakeHandle(ServiceLaunch launch) {
            this.launch = launch;
        }

        @Override
        public ServiceId id() {
            return launch.id();
        }

        @Override
        public String nativeId() {
            return "fake";
        }

        @Override
        public Path directory() {
            return launch.directory();
        }

        @Override
        public boolean isAlive() {
            return true;
        }
    }
}
