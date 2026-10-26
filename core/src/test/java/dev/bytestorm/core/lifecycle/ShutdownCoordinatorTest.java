package dev.bytestorm.core.lifecycle;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShutdownCoordinatorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void runsStepsInReverseRegistrationOrder() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        ShutdownCoordinator coordinator = new ShutdownCoordinator(TIMEOUT);
        coordinator.register("controller", () -> order.add("controller"));
        coordinator.register("node", () -> order.add("node"));
        coordinator.register("cli", () -> order.add("cli"));

        coordinator.shutdown();

        assertEquals(List.of("cli", "node", "controller"), order);
        assertTrue(coordinator.isShutdown());
    }

    @Test
    void shutdownIsIdempotent() {
        AtomicInteger runs = new AtomicInteger();
        ShutdownCoordinator coordinator = new ShutdownCoordinator(TIMEOUT);
        coordinator.register("step", runs::incrementAndGet);

        coordinator.shutdown();
        coordinator.shutdown();
        coordinator.close();

        assertEquals(1, runs.get());
    }

    @Test
    void failingStepDoesNotStopRemainingSteps() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        ShutdownCoordinator coordinator = new ShutdownCoordinator(TIMEOUT);
        coordinator.register("persistence", () -> order.add("persistence"));
        coordinator.register("crashy", () -> {
            order.add("crashy");
            throw new IllegalStateException("kaputt");
        });

        coordinator.shutdown();

        assertEquals(List.of("crashy", "persistence"), order);
    }

    @Test
    void hangingStepTimesOutAndShutdownContinues() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        ShutdownCoordinator coordinator = new ShutdownCoordinator(Duration.ofMillis(150));
        coordinator.register("fast", () -> order.add("fast"));
        coordinator.register("hangs", () -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        long started = System.nanoTime();
        coordinator.shutdown();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertEquals(List.of("fast"), order);
        assertTrue(elapsedMillis < 5_000, "Timeout wurde nicht angewandt: " + elapsedMillis + " ms");
    }

    @Test
    void registersAutoCloseableResources() {
        AtomicBoolean closed = new AtomicBoolean();
        ShutdownCoordinator coordinator = new ShutdownCoordinator(TIMEOUT);

        coordinator.registerCloseable("store", () -> closed.set(true));
        assertEquals(1, coordinator.size());

        coordinator.shutdown();

        assertTrue(closed.get());
    }

    @Test
    void rejectsDuplicateNames() {
        ShutdownCoordinator coordinator = new ShutdownCoordinator(TIMEOUT);
        coordinator.register("node", () -> {
        });

        assertThrows(IllegalArgumentException.class, () -> coordinator.register("node", () -> {
        }));
    }

    @Test
    void rejectsRegistrationAfterShutdownStarted() {
        ShutdownCoordinator coordinator = new ShutdownCoordinator(TIMEOUT);
        coordinator.shutdown();

        assertThrows(IllegalStateException.class, () -> coordinator.register("late", () -> {
        }));
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new ShutdownCoordinator(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ShutdownCoordinator(Duration.ofMillis(-1)));
    }
}
