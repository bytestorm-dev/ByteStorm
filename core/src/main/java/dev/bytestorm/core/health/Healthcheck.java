package dev.bytestorm.core.health;

import dev.bytestorm.core.mongo.MongoProvider;
import dev.bytestorm.core.redis.RedisProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Healthcheck-Fassade für CLI und REST-Modul.
 *
 * <p>MongoDB und Redis werden direkt aus {@code core} geprüft. Die Runtime ist
 * Sache des Nodes und wird deshalb als {@link HealthProbe} hereingereicht
 * ({@link #withRuntime}); ohne sie enthält der Bericht nur die beiden
 * Persistenz-Bestandteile.
 *
 * <p>Alle Prüfungen laufen parallel auf Virtual Threads und teilen sich eine
 * gemeinsame Frist: Ein hängender Bestandteil darf den Healthcheck nicht
 * blockieren, und die Gesamtdauer bleibt in der Größenordnung des Timeouts.
 * Der {@link Healthcheck} ist unveränderlich und thread-safe.
 */
public final class Healthcheck {

    /** Bestandteil: MongoDB. */
    public static final String MONGO = "mongo";

    /** Bestandteil: Redis. */
    public static final String REDIS = "redis";

    /** Bestandteil: Runtime (Prozess oder Docker). */
    public static final String RUNTIME = "runtime";

    /** Default-Frist für einen vollständigen Healthcheck. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final Map<String, HealthProbe> probes;

    /**
     * @param probes Prüfungen je Bestandteil; Reihenfolge bestimmt die Ausgabe
     * @throws IllegalArgumentException wenn die Map leer ist oder ein Name leer ist
     */
    public Healthcheck(Map<String, HealthProbe> probes) {
        Objects.requireNonNull(probes, "probes");
        if (probes.isEmpty()) {
            throw new IllegalArgumentException("Mindestens eine Prüfung ist nötig");
        }

        Map<String, HealthProbe> copy = new LinkedHashMap<>();
        for (Map.Entry<String, HealthProbe> entry : probes.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Prüfungsname darf nicht leer sein");
            }
            copy.put(name, Objects.requireNonNull(entry.getValue(), "Probe für '" + name + "'"));
        }
        this.probes = Collections.unmodifiableMap(copy);
    }

    /**
     * Prüft MongoDB und Redis.
     *
     * @param mongo verbundener Mongo-Provider
     * @param redis verbundener Redis-Provider
     * @return Fassade ohne Runtime-Prüfung
     */
    public static Healthcheck of(MongoProvider mongo, RedisProvider redis) {
        Objects.requireNonNull(mongo, "mongo");
        Objects.requireNonNull(redis, "redis");

        Map<String, HealthProbe> probes = new LinkedHashMap<>();
        probes.put(MONGO, mongo::ping);
        probes.put(REDIS, redis::ping);
        return new Healthcheck(probes);
    }

    /**
     * Ergänzt die Runtime-Prüfung. Die bestehende Fassade bleibt unverändert —
     * der Node reicht z. B. einen Prozess- oder Docker-Check herein.
     *
     * @param runtime Prüfung der Runtime
     * @return neue Fassade inklusive Runtime
     */
    public Healthcheck withRuntime(HealthProbe runtime) {
        Objects.requireNonNull(runtime, "runtime");
        Map<String, HealthProbe> next = new LinkedHashMap<>(probes);
        next.put(RUNTIME, runtime);
        return new Healthcheck(next);
    }

    /**
     * Prüft alle Bestandteile mit {@link #DEFAULT_TIMEOUT}.
     *
     * @return der Bericht
     */
    public HealthReport check() {
        return check(DEFAULT_TIMEOUT);
    }

    /**
     * Prüft alle Bestandteile parallel innerhalb einer gemeinsamen Frist.
     *
     * @param timeout Frist für den gesamten Healthcheck
     * @return der Bericht; hängende Bestandteile erscheinen als nicht verfügbar
     * @throws IllegalArgumentException wenn {@code timeout} nicht positiv ist
     */
    public HealthReport check(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout muss positiv sein: " + timeout);
        }

        Instant checkedAt = Instant.now();
        long startedNanos = System.nanoTime();
        long deadlineNanos = startedNanos + timeout.toNanos();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Map<String, Future<ComponentHealth>> futures = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, HealthProbe> entry : probes.entrySet()) {
                String name = entry.getKey();
                HealthProbe probe = entry.getValue();
                futures.put(name, executor.submit(() -> run(name, probe)));
            }

            Map<String, ComponentHealth> results = new LinkedHashMap<>();
            for (Map.Entry<String, Future<ComponentHealth>> entry : futures.entrySet()) {
                results.put(entry.getKey(), await(entry.getKey(), entry.getValue(), deadlineNanos, timeout));
            }
            return new HealthReport(checkedAt, Duration.ofNanos(System.nanoTime() - startedNanos), results);
        } finally {
            futures.values().forEach(future -> future.cancel(true));
            executor.shutdownNow();
        }
    }

    private static ComponentHealth run(String name, HealthProbe probe) {
        try {
            probe.check();
            return new ComponentHealth(name, true, "OK");
        } catch (Exception e) {
            return new ComponentHealth(name, false, describe(e));
        }
    }

    private static ComponentHealth await(String name,
                                         Future<ComponentHealth> future,
                                         long deadlineNanos,
                                         Duration timeout) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            future.cancel(true);
            return timeoutHealth(name, timeout);
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return timeoutHealth(name, timeout);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new ComponentHealth(name, false, describe(cause));
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new ComponentHealth(name, false, "Unterbrochen");
        }
    }

    private static ComponentHealth timeoutHealth(String name, Duration timeout) {
        return new ComponentHealth(name, false, "Timeout nach " + timeout.toMillis() + " ms");
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
