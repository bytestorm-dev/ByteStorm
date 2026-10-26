package dev.bytestorm.core.health;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthcheckTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void reportsEveryComponentHealthyInRegistrationOrder() {
        Healthcheck healthcheck = twoProbes(noOp(), noOp());

        HealthReport report = healthcheck.check(TIMEOUT);

        assertTrue(report.up());
        assertTrue(report.unhealthy().isEmpty());
        assertEquals(List.of(Healthcheck.MONGO, Healthcheck.REDIS), List.copyOf(report.components().keySet()));
        for (ComponentHealth component : report.components().values()) {
            assertTrue(component.healthy());
            assertEquals("OK", component.detail());
        }
        assertNotNull(report.checkedAt());
    }

    @Test
    void failingProbeMarksOnlyThatComponentDown() {
        Healthcheck healthcheck = twoProbes(noOp(), () -> {
            throw new IllegalStateException("Redis antwortet nicht");
        });

        HealthReport report = healthcheck.check(TIMEOUT);

        assertFalse(report.up());
        assertTrue(report.component(Healthcheck.MONGO).orElseThrow().healthy());
        ComponentHealth redis = report.component(Healthcheck.REDIS).orElseThrow();
        assertFalse(redis.healthy());
        assertEquals("Redis antwortet nicht", redis.detail());
        assertEquals(List.of(redis), report.unhealthy());
    }

    @Test
    void fallsBackToTheExceptionTypeWithoutMessage() {
        Healthcheck healthcheck = oneProbe(() -> {
            throw new IllegalStateException();
        });

        ComponentHealth mongo = healthcheck.check(TIMEOUT).component(Healthcheck.MONGO).orElseThrow();

        assertFalse(mongo.healthy());
        assertEquals("IllegalStateException", mongo.detail());
    }

    @Test
    void hangingProbeTimesOutAndDoesNotBlockTheReport() {
        Healthcheck healthcheck = twoProbes(noOp(), () -> Thread.sleep(30_000));

        long started = System.nanoTime();
        HealthReport report = healthcheck.check(Duration.ofMillis(150));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertFalse(report.up());
        ComponentHealth redis = report.component(Healthcheck.REDIS).orElseThrow();
        assertFalse(redis.healthy());
        assertEquals("Timeout nach 150 ms", redis.detail());
        assertTrue(report.component(Healthcheck.MONGO).orElseThrow().healthy());
        assertTrue(elapsedMillis < 3_000, "Timeout wurde nicht angewandt: " + elapsedMillis + " ms");
    }

    @Test
    void runsProbesInParallel() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        HealthProbe rendezvous = () -> {
            bothStarted.countDown();
            if (!bothStarted.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Prüfungen liefen nicht parallel");
            }
        };
        Healthcheck healthcheck = twoProbes(rendezvous, rendezvous);

        HealthReport report = healthcheck.check(Duration.ofSeconds(2));

        assertTrue(report.up(), () -> "Bericht: " + report);
    }

    @Test
    void addsTheRuntimeProbeWithoutChangingTheOriginalInstance() {
        Healthcheck persistence = oneProbe(noOp());

        Healthcheck withRuntime = persistence.withRuntime(noOp());

        assertEquals(List.of(Healthcheck.MONGO), List.copyOf(persistence.check(TIMEOUT).components().keySet()));
        assertTrue(withRuntime.check(TIMEOUT).component(Healthcheck.RUNTIME).orElseThrow().healthy());
        assertTrue(withRuntime.check(TIMEOUT).up());
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new Healthcheck(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Healthcheck(probes(" ", noOp())));
        assertThrows(NullPointerException.class, () -> new Healthcheck(probes("mongo", null)));

        Healthcheck healthcheck = oneProbe(noOp());
        assertThrows(IllegalArgumentException.class, () -> healthcheck.check(Duration.ZERO));
        assertThrows(NullPointerException.class, () -> healthcheck.check(null));
    }

    @Test
    void reportIsImmutable() {
        HealthReport report = oneProbe(noOp()).check(TIMEOUT);

        assertThrows(UnsupportedOperationException.class,
                () -> report.components().put("x", new ComponentHealth("x", true, "OK")));
        assertEquals(1, report.components().size());
    }

    private static HealthProbe noOp() {
        return () -> {
        };
    }

    private static Healthcheck oneProbe(HealthProbe mongo) {
        return new Healthcheck(probes(Healthcheck.MONGO, mongo));
    }

    private static Healthcheck twoProbes(HealthProbe mongo, HealthProbe redis) {
        Map<String, HealthProbe> probes = probes(Healthcheck.MONGO, mongo);
        probes.put(Healthcheck.REDIS, redis);
        return new Healthcheck(probes);
    }

    private static Map<String, HealthProbe> probes(String name, HealthProbe probe) {
        Map<String, HealthProbe> probes = new LinkedHashMap<>();
        probes.put(name, probe);
        return probes;
    }
}
