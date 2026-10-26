package dev.bytestorm.core.lifecycle;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Geordnetes Herunterfahren der Cloud (§3.6).
 *
 * <p>Schritte werden in umgekehrter Registrierungsreihenfolge ausgeführt: Wer
 * zuletzt startet (CLI), wird zuerst beendet, die Persistenz zuletzt. Jeder
 * Schritt läuft mit Timeout; Fehler oder Timeouts werden geloggt und stoppen die
 * übrigen Schritte nicht. Mehrfache Aufrufe von {@link #shutdown()} sind
 * wirkungslos.
 */
@Slf4j
public final class ShutdownCoordinator implements AutoCloseable {

    /** Default-Timeout je Schritt. */
    public static final Duration DEFAULT_STEP_TIMEOUT = Duration.ofSeconds(30);

    private final Duration stepTimeout;
    private final List<Step> steps = new ArrayList<>();
    private final Set<String> names = new HashSet<>();
    private final AtomicBoolean shutdown = new AtomicBoolean();

    /**
     * Erstellt den Koordinator mit {@link #DEFAULT_STEP_TIMEOUT}.
     */
    public ShutdownCoordinator() {
        this(DEFAULT_STEP_TIMEOUT);
    }

    /**
     * @param stepTimeout Timeout je Schritt, muss positiv sein
     */
    public ShutdownCoordinator(Duration stepTimeout) {
        Objects.requireNonNull(stepTimeout, "stepTimeout");
        if (stepTimeout.isZero() || stepTimeout.isNegative()) {
            throw new IllegalArgumentException("stepTimeout muss positiv sein: " + stepTimeout);
        }
        this.stepTimeout = stepTimeout;
    }

    /**
     * Registriert einen Schritt. Die Reihenfolge wird beim Herunterfahren
     * umgedreht.
     *
     * @param name   eindeutiger Name für Log und Diagnose
     * @param action auszuführende Aktion
     * @return this
     * @throws IllegalArgumentException bei doppeltem Namen
     * @throws IllegalStateException    wenn bereits heruntergefahren wird
     */
    public synchronized ShutdownCoordinator register(String name, Runnable action) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(action, "action");
        if (shutdown.get()) {
            throw new IllegalStateException("Shutdown läuft bereits, Schritt '" + name + "' kann nicht mehr registriert werden");
        }
        if (!names.add(name)) {
            throw new IllegalArgumentException("Schritt '" + name + "' ist bereits registriert");
        }
        steps.add(new Step(name, action));
        return this;
    }

    /**
     * Registriert ein {@link AutoCloseable}, z. B. einen Provider. Eigener
     * Methodenname, weil {@code register(name, () -> ...)} sonst zwischen
     * {@link Runnable} und {@link AutoCloseable} mehrdeutig wäre.
     *
     * @param name      eindeutiger Name
     * @param closeable Ressource
     * @return this
     */
    public ShutdownCoordinator registerCloseable(String name, AutoCloseable closeable) {
        Objects.requireNonNull(closeable, "closeable");
        return register(name, () -> {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IllegalStateException("Close fehlgeschlagen: " + name, e);
            }
        });
    }

    /**
     * Führt alle Schritte in umgekehrter Reihenfolge aus. Idempotent.
     */
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        List<Step> ordered;
        synchronized (this) {
            ordered = new ArrayList<>(steps);
        }
        Collections.reverse(ordered);
        log.info("Fahre Cloud herunter ({} Schritte)", ordered.size());

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (Step step : ordered) {
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("Shutdown unterbrochen — verbleibende Schritte werden übersprungen");
                    break;
                }
                runStep(executor, step);
            }
        } finally {
            executor.shutdownNow();
        }
        log.info("Shutdown abgeschlossen");
    }

    /**
     * @return {@code true}, sobald {@link #shutdown()} begonnen hat
     */
    public boolean isShutdown() {
        return shutdown.get();
    }

    /**
     * @return Anzahl registrierter Schritte
     */
    public synchronized int size() {
        return steps.size();
    }

    @Override
    public void close() {
        shutdown();
    }

    private void runStep(ExecutorService executor, Step step) {
        Future<?> future = executor.submit(() -> {
            step.action().run();
            return null;
        });
        try {
            future.get(stepTimeout.toMillis(), TimeUnit.MILLISECONDS);
            log.debug("Schritt '{}' abgeschlossen", step.name());
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("Schritt '{}' überschritt {} — fahre mit den übrigen Schritten fort", step.name(), stepTimeout);
        } catch (ExecutionException e) {
            log.error("Schritt '{}' fehlgeschlagen", step.name(), e.getCause());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("Warten auf Schritt '{}' unterbrochen", step.name());
        }
    }

    private record Step(String name, Runnable action) {
    }
}
