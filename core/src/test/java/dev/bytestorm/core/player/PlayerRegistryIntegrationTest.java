package dev.bytestorm.core.player;

import dev.bytestorm.codec.ProtobufCodec;
import dev.bytestorm.codec.TypeRegistry;
import dev.bytestorm.core.config.MongoSettings;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.core.event.EventBus;
import dev.bytestorm.core.mongo.MongoProvider;
import dev.bytestorm.core.mongo.model.StoredPlayer;
import dev.bytestorm.core.mongo.repository.PlayerRepository;
import dev.bytestorm.core.redis.PlayerStore;
import dev.bytestorm.core.redis.RedisProvider;
import dev.bytestorm.core.support.TestServers;
import dev.bytestorm.proto.PlayerInfo;
import dev.bytestorm.proto.PlayerJoined;
import dev.bytestorm.proto.PlayerLeft;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlayerRegistryIntegrationTest {

    private MongoProvider mongo;
    private RedisProvider redis;
    private EventBus events;
    private PlayerStore store;
    private PlayerRepository repository;
    private PlayerRegistry registry;
    private MutableClock clock;

    @BeforeAll
    void connect() {
        TestServers.assumeMongo();
        TestServers.assumeRedis();

        mongo = MongoProvider.connect(new MongoSettings(TestServers.mongoUri(), TestServers.uniqueDatabase()));
        TypeRegistry typeRegistry = TypeRegistry.byteStormDefaults();
        redis = RedisProvider.connect(new RedisSettings(TestServers.redisAddress(), null),
                new ProtobufCodec(typeRegistry));
        events = new EventBus(redis.client(), typeRegistry);
        store = new PlayerStore(redis.client());
        repository = new PlayerRepository(mongo);
        clock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        registry = new PlayerRegistry(events, store, repository, clock);
    }

    @AfterAll
    void cleanup() {
        if (registry != null) {
            registry.close();
        }
        if (events != null) {
            events.close();
        }
        if (redis != null) {
            redis.client().getKeys().deleteByPattern("bs:*");
            redis.close();
        }
        if (mongo != null) {
            mongo.database().drop();
            mongo.close();
        }
    }

    @Test
    void joinWritesRedisAndMongoAndQuitAddsPlaytime() throws Exception {
        UUID uuid = UUID.randomUUID();
        PlayerInfo player = PlayerInfo.newBuilder()
                .setUuid(uuid.toString())
                .setName("Steve")
                .setProxy("Proxy-1")
                .setServer("Lobby-1")
                .build();

        events.publish(PlayerJoined.newBuilder().setPlayer(player).build());

        await(() -> store.get(uuid.toString()).isPresent(), "Spieler nicht in Redis");
        StoredPlayer joined = awaitStored(uuid);
        assertEquals("Steve", joined.name());
        assertEquals("Lobby-1", joined.lastService());

        clock.advance(Duration.ofSeconds(90));
        events.publish(PlayerLeft.newBuilder().setPlayer(player).build());

        await(() -> store.get(uuid.toString()).isEmpty(), "Spieler blieb in Redis");
        StoredPlayer left = awaitPlaytime(uuid, Duration.ofSeconds(90));
        assertTrue(left.lastSeen().isAfter(joined.firstSeen()));
    }

    private StoredPlayer awaitPlaytime(UUID uuid, Duration expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var stored = repository.find(uuid);
            if (stored.isPresent() && stored.get().playtime().equals(expected)) {
                return stored.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Spielzeit " + expected + " nicht in MongoDB");
    }

    private StoredPlayer awaitStored(UUID uuid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var stored = repository.find(uuid);
            if (stored.isPresent()) {
                return stored.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Spieler " + uuid + " nicht in MongoDB");
    }

    private static void await(Check check, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
