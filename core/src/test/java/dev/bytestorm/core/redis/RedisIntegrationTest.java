package dev.bytestorm.core.redis;

import dev.bytestorm.api.event.Subscription;
import dev.bytestorm.api.service.PortRange;
import dev.bytestorm.codec.ProtobufCodec;
import dev.bytestorm.codec.TypeRegistry;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.core.support.TestServers;
import dev.bytestorm.proto.LogLine;
import dev.bytestorm.proto.PlayerInfo;
import dev.bytestorm.proto.ScheduleRule;
import dev.bytestorm.proto.ScalingRule;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisIntegrationTest {

    private RedisProvider provider;
    private ProtobufCodec codec;

    @BeforeAll
    void connect() {
        TestServers.assumeRedis();
        codec = new ProtobufCodec(TypeRegistry.byteStormDefaults());
        provider = RedisProvider.connect(new RedisSettings(TestServers.redisAddress(), null), codec);
    }

    @AfterAll
    void cleanup() {
        if (provider != null) {
            provider.client().getKeys().deleteByPattern("bs:*");
            provider.close();
        }
    }

    private static ServiceInfo service(String id, String name, String group, int port) {
        return ServiceInfo.newBuilder()
                .setId(id)
                .setName(name)
                .setGroup(group)
                .setHost("127.0.0.1")
                .setPort(port)
                .setState(ServiceState.ONLINE)
                .setMaxPlayers(100)
                .build();
    }

    @Test
    void codecWorksThroughRedissonRoundtrip() {
        ServiceInfo info = service(UUID.randomUUID().toString(), "Lobby-1", "Lobby", 30001);

        provider.client().getBucket("bs:test:bucket").set(info);

        Object loaded = provider.client().getBucket("bs:test:bucket").get();
        assertEquals(info, loaded);
    }

    @Test
    void serviceRegistryStoresGroupsAndExpiresEntries() throws Exception {
        ServiceRegistryStore store = new ServiceRegistryStore(provider.client());
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();

        store.put(service(first, "Lobby-1", "Lobby", 30001), Duration.ofSeconds(30));
        store.put(service(second, "Lobby-2", "Lobby", 30002), Duration.ofSeconds(30));

        assertEquals(2, store.byGroup("Lobby").size());
        assertTrue(store.get(first).isPresent());
        assertEquals("Lobby-1", store.get(first).orElseThrow().getName());

        store.remove(first);
        assertTrue(store.get(first).isEmpty());

        String expiring = UUID.randomUUID().toString();
        store.put(service(expiring, "Lobby-3", "Lobby", 30003), Duration.ofMillis(300));
        Thread.sleep(1_500);
        assertTrue(store.get(expiring).isEmpty(), "Eintrag muss nach TTL verfallen");
    }

    @Test
    void groupCacheStoresGroupsAndInvalidates() {
        RedisGroupCache cache = new RedisGroupCache(provider.client(), Duration.ofSeconds(30));
        ServiceGroup group = ServiceGroup.newBuilder()
                .setName("Lobby")
                .setType(ServiceType.SERVER)
                .setPlatform("paper")
                .addTemplates("Lobby")
                .putProperties("motd", "Hallo")
                .addScalingRules(ScalingRule.newBuilder()
                        .setId("zeit")
                        .setEnabled(true)
                        .setSchedule(ScheduleRule.newBuilder().setCron("* 18-23 * * *").setMinServices(3).build()))
                .build();

        cache.put(group);

        assertEquals(group, cache.get("Lobby").orElseThrow(),
                "verschachtelte Regeln und Properties überstehen den Cache");
        assertTrue(cache.invalidate("Lobby"));
        assertTrue(cache.get("Lobby").isEmpty());
        assertFalse(cache.invalidate("Lobby"));
    }

    @Test
    void reconciliationRemovesOrphansReleasesPortsAndAlignsCounters() {
        ServiceRegistryStore registry = new ServiceRegistryStore(provider.client());
        PortAllocator ports = new PortAllocator(provider.client());
        NameAllocator names = new NameAllocator(provider.client());
        ServiceReconciliation reconciliation = new ServiceReconciliation(registry, ports, names);
        PortRange range = new PortRange(39000, 39999);
        String group = "Recon" + Long.toHexString(System.nanoTime());

        String orphanId = UUID.randomUUID().toString();
        registry.put(service(orphanId, "Alte-Lobby-1", "Alte-Lobby", 39512), Duration.ofSeconds(30));
        assertTrue(ports.reserve(range, 39512));
        assertTrue(ports.reserve(range, 39513));

        // Der Node kennt keinen Service; Port 39513 gehört zu einem bekannten Service.
        ServiceReconciliation.Report report = reconciliation.run(
                Set.of(), Set.of(39513), Set.of(group + "-7", "Proxy"));

        assertTrue(report.removedServices().contains("Alte-Lobby-1"));
        assertTrue(registry.get(orphanId).isEmpty());
        assertTrue(report.releasedPorts().contains(39512));
        assertFalse(report.releasedPorts().contains(39513), "bekannte Ports bleiben reserviert");
        assertEquals(Map.of(group, 7), report.alignedCounters(), "Namen ohne Nummer werden ignoriert");
        assertEquals(7, names.current(group));
        assertFalse(ports.allocated().contains(39512));
        assertTrue(ports.allocated().contains(39513));

        ServiceReconciliation.Report again = reconciliation.run(Set.of(), Set.of(39513), Set.of(group + "-7"));
        assertTrue(again.alignedCounters().isEmpty(), "ein zweiter Abgleich verändert den Zähler nicht");
    }

    @Test
    void playerStoreCrud() {
        PlayerStore store = new PlayerStore(provider.client());
        String uuid = UUID.randomUUID().toString();
        PlayerInfo player = PlayerInfo.newBuilder()
                .setUuid(uuid)
                .setName("Notch")
                .setProxy("Proxy-1")
                .setServer("Lobby-1")
                .build();

        store.put(player);

        assertEquals(player, store.get(uuid).orElseThrow());
        assertEquals(1, store.size());
        assertTrue(store.all().stream().anyMatch(p -> p.getName().equals("Notch")));
        assertTrue(store.remove(uuid));
        assertTrue(store.get(uuid).isEmpty());
    }

    @Test
    void portAllocatorHandsOutDistinctPorts() {
        PortAllocator allocator = new PortAllocator(provider.client());
        PortRange range = new PortRange(45500, 45600);

        int first = allocator.allocate(range);
        int second = allocator.allocate(range);

        assertTrue(first != second);
        assertTrue(range.contains(first) && range.contains(second));
        assertTrue(allocator.allocated().containsAll(Set.of(first, second)));

        assertFalse(allocator.reserve(range, first), "bereits vergebener Port darf nicht reserviert werden");

        assertTrue(allocator.release(first));
        assertTrue(allocator.release(second));
        assertFalse(allocator.allocated().contains(first));
    }

    @Test
    void portAllocatorIsSafeUnderConcurrency() throws Exception {
        PortAllocator allocator = new PortAllocator(provider.client());
        PortRange range = new PortRange(46000, 46100);
        int workers = 12;

        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                tasks.add(() -> allocator.allocate(range));
            }
            List<Future<Integer>> futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);
            Set<Integer> ports = new HashSet<>();
            for (Future<Integer> future : futures) {
                ports.add(future.get());
            }

            assertEquals(workers, ports.size(), "keine Doppelvergabe erlaubt");
            ports.forEach(allocator::release);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void nameAllocatorReusesSmallestFreeNumber() {
        NameAllocator allocator = new NameAllocator(provider.client());
        String group = "NameTest-" + UUID.randomUUID();

        assertEquals(1, allocator.allocate(group, number -> false));
        assertEquals(3, allocator.allocate(group, number -> number <= 2));
        assertEquals(2, allocator.allocate(group, number -> number != 2));
        assertTrue(allocator.current(group) >= 3);
    }

    @Test
    void cacheServiceHonoursTtl() throws Exception {
        CacheService cache = new CacheService(provider.client());

        cache.put("groups", "Lobby", "hash-1", Duration.ofSeconds(30));
        assertEquals("hash-1", cache.get("groups", "Lobby").orElseThrow());

        cache.put("groups", "Short", "gone", Duration.ofMillis(300));
        Thread.sleep(1_200);
        assertTrue(cache.get("groups", "Short").isEmpty());

        assertTrue(cache.remove("groups", "Lobby"));
        cache.clear("groups");
        assertTrue(cache.get("groups", "Lobby").isEmpty());
    }

    @Test
    void logTopicDeliversLines() throws Exception {
        LogTopic topic = new LogTopic(provider.client());
        CountDownLatch latch = new CountDownLatch(1);
        List<String> received = new ArrayList<>();

        try (Subscription subscription = topic.subscribe("Lobby-1", line -> {
            received.add(line.getLine());
            latch.countDown();
        })) {
            assertTrue(subscription.isActive());
            topic.publish(LogLine.newBuilder()
                    .setService("Lobby-1")
                    .setTime(System.currentTimeMillis())
                    .setLine("Done (2.341s)!")
                    .build());

            assertTrue(latch.await(10, TimeUnit.SECONDS), "Logzeile nicht empfangen");
            assertEquals(List.of("Done (2.341s)!"), received);
        }

        assertFalse(provider.client().getTopic(RedisKeys.log("Lobby-1")).countListeners() > 0,
                "Subscription muss entfernt sein");
    }

    @Test
    void portAllocatorReportsExhaustion() {
        PortAllocator allocator = new PortAllocator(provider.client());
        PortRange range = new PortRange(46200, 46201);

        int first = allocator.allocate(range);
        int second = allocator.allocate(range);
        try {
            assertThrows(PortExhaustedException.class, () -> allocator.allocate(range));
        } finally {
            allocator.release(first);
            allocator.release(second);
        }
    }

    @Test
    void healthKeyIsUsable() {
        provider.ping();
        assertFalse(provider.client().getBucket(RedisKeys.HEALTH).isExists());
    }
}
