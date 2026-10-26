package dev.bytestorm.core.event;

import dev.bytestorm.api.event.Subscription;
import dev.bytestorm.codec.ProtobufCodec;
import dev.bytestorm.codec.TypeRegistry;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.core.redis.RedisProvider;
import dev.bytestorm.core.support.TestServers;
import dev.bytestorm.proto.GroupUpdated;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceStateChanged;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventBusIntegrationTest {

    private RedisProvider provider;
    private EventBus bus;

    @BeforeAll
    void connect() {
        TestServers.assumeRedis();
        TypeRegistry registry = TypeRegistry.byteStormDefaults();
        provider = RedisProvider.connect(new RedisSettings(TestServers.redisAddress(), null),
                new ProtobufCodec(registry));
        bus = new EventBus(provider.client(), registry);
    }

    @AfterAll
    void cleanup() {
        if (bus != null) {
            bus.close();
        }
        if (provider != null) {
            provider.client().getKeys().deleteByPattern("bs:*");
            provider.close();
        }
    }

    private static ServiceStateChanged change(String name, String group) {
        return ServiceStateChanged.newBuilder()
                .setService(ServiceInfo.newBuilder()
                        .setId("id-" + name)
                        .setName(name)
                        .setGroup(group)
                        .setState(ServiceState.ONLINE))
                .setPrevious(ServiceState.STARTING)
                .build();
    }

    @Test
    void publishDeliversToSubscriber() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ServiceStateChanged> received = new AtomicReference<>();

        try (Subscription ignored = bus.subscribe(ServiceStateChanged.class, event -> {
            received.set(event);
            latch.countDown();
        })) {
            bus.publish(change("Lobby-1", "Lobby"));

            assertTrue(latch.await(10, TimeUnit.SECONDS), "Event nicht empfangen");
            assertEquals("Lobby-1", received.get().getService().getName());
            assertEquals(ServiceState.STARTING, received.get().getPrevious());
        }
    }

    @Test
    void filterBlocksNonMatchingEvents() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger matches = new AtomicInteger();

        try (Subscription ignored = bus.subscribe(ServiceStateChanged.class,
                event -> event.getService().getGroup().equals("Lobby"),
                event -> {
                    matches.incrementAndGet();
                    latch.countDown();
                })) {
            bus.publish(change("Proxy-1", "Proxy"));
            bus.publish(change("Lobby-1", "Lobby"));

            assertTrue(latch.await(10, TimeUnit.SECONDS), "passendes Event nicht empfangen");
            Thread.sleep(300);
            assertEquals(1, matches.get(), "gefilterte Events dürfen den Listener nicht erreichen");
        }
    }

    @Test
    void unsubscribeStopsDelivery() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Subscription subscription = bus.subscribe(ServiceStateChanged.class, event -> latch.countDown());

        subscription.unsubscribe();

        assertFalse(subscription.isActive());
        bus.publish(change("Lobby-1", "Lobby"));
        assertFalse(latch.await(700, TimeUnit.MILLISECONDS), "nach unsubscribe darf nichts mehr ankommen");
    }

    @Test
    void failingListenerDoesNotAffectOthers() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        try (Subscription first = bus.subscribe(ServiceStateChanged.class, event -> {
            throw new IllegalStateException("absichtlich");
        });
             Subscription second = bus.subscribe(ServiceStateChanged.class, event -> latch.countDown())) {
            bus.publish(change("Lobby-1", "Lobby"));

            assertTrue(latch.await(10, TimeUnit.SECONDS), "zweiter Listener muss trotz Fehler laufen");
        }
    }

    @Test
    void eventsOfOtherTypesAreNotDelivered() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        try (Subscription ignored = bus.subscribe(ServiceStateChanged.class, event -> latch.countDown())) {
            bus.publish(GroupUpdated.newBuilder().setGroup("Lobby").build());
            assertFalse(latch.await(600, TimeUnit.MILLISECONDS), "fremder Eventtyp darf nicht ankommen");

            bus.publish(change("Lobby-1", "Lobby"));
            assertTrue(latch.await(10, TimeUnit.SECONDS), "eigener Eventtyp muss ankommen");
        }
    }

    @Test
    void closeDeactivatesSubscriptions() {
        Subscription subscription = bus.subscribe(ServiceStateChanged.class, event -> {
        });

        bus.close();

        assertFalse(subscription.isActive());
        assertEquals(0, bus.subscriptionCount());
    }
}
