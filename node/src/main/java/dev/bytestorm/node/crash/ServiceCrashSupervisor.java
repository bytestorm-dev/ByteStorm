package dev.bytestorm.node.crash;

import dev.bytestorm.core.config.CrashSettings;
import dev.bytestorm.node.lifecycle.ServiceExit;
import dev.bytestorm.node.lifecycle.ServiceInstance;
import dev.bytestorm.node.lifecycle.ServiceLifecycleException;
import dev.bytestorm.node.runtime.PortLease;
import dev.bytestorm.proto.ServiceCrashed;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Verbindet einen {@link ServiceInstance} mit dem {@link CrashHandler} (§12.5).
 *
 * <p>Ein unerwarteter Exit schreibt den Bericht, publiziert {@code ServiceCrashed}
 * und startet den Service nach dem konfigurierten Backoff neu. Sind die
 * Versuche erschöpft oder scheitert ein Neustart, wird die Gruppe über den
 * injizierten Callback als {@code DEGRADED} gemeldet. Nach fünf Minuten
 * {@code ONLINE} werden die Versuche zurückgesetzt.
 */
public final class ServiceCrashSupervisor {

    private static final Logger log = LoggerFactory.getLogger(ServiceCrashSupervisor.class);

    /**
     * Default-Frist für den Übergang nach {@code ONLINE}.
     *
     * <p>Paper braucht beim ersten Start mit Weltgenerierung und Plugin-Laden
     * deutlich länger als eine Minute. Mit 60 s wurde ein laufender Server als
     * „Start-Timeout“ gekillt, im Backoff neu gestartet und die Versuche waren
     * aufgebraucht, bevor er je {@code ONLINE} melden konnte.
     */
    public static final Duration DEFAULT_START_TIMEOUT = Duration.ofMinutes(3);

    private final ServiceInstance instance;
    private final CrashHandler crashes;
    private final CrashHandler.Scheduler scheduler;
    private final Consumer<String> degradedGroups;
    private final Duration startTimeout;
    private final Duration onlineReset;
    private final AtomicLong generation = new AtomicLong();

    /**
     * @param instance        Service
     * @param settings        Restart-Konfiguration
     * @param reports         Crash-Berichte
     * @param events          Publisher für {@code ServiceCrashed}
     * @param scheduler       Zeitplan für Backoff und Online-Reset
     * @param degradedGroups  meldet eine erschöpfte Gruppe
     */
    public ServiceCrashSupervisor(ServiceInstance instance,
                                  CrashSettings settings,
                                  CrashReportWriter reports,
                                  Consumer<ServiceCrashed> events,
                                  CrashHandler.Scheduler scheduler,
                                  Consumer<String> degradedGroups) {
        this(instance, settings, reports, events, scheduler, degradedGroups,
                DEFAULT_START_TIMEOUT, RestartPolicy.DEFAULT_ONLINE_RESET);
    }

    ServiceCrashSupervisor(ServiceInstance instance,
                           CrashSettings settings,
                           CrashReportWriter reports,
                           Consumer<ServiceCrashed> events,
                           CrashHandler.Scheduler scheduler,
                           Consumer<String> degradedGroups,
                           Duration startTimeout,
                           Duration onlineReset) {
        this.instance = Objects.requireNonNull(instance, "instance");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.degradedGroups = Objects.requireNonNull(degradedGroups, "degradedGroups");
        this.startTimeout = Objects.requireNonNull(startTimeout, "startTimeout");
        this.onlineReset = Objects.requireNonNull(onlineReset, "onlineReset");
        if (startTimeout.isZero() || startTimeout.isNegative()) {
            throw new IllegalArgumentException("startTimeout muss positiv sein: " + startTimeout);
        }

        CrashHandler.Actions actions = new CrashHandler.Actions() {
            @Override
            public void restart(ServiceInfo service) {
                restartIfStillCrashed();
            }

            @Override
            public void markDegraded(String group) {
                degradedGroups.accept(group);
            }
        };
        this.crashes = new CrashHandler(settings, reports, events, scheduler, actions);
        instance.onExit(this::onExit);
    }

    /**
     * Startet den Service von Hand und gibt ihm sein volles Absturz-Budget
     * zurück. Automatische Neustarts laufen über {@code startInstance} und
     * zählen weiter, bis die Versuche erschöpft sind.
     *
     * @return der Service im Zustand {@code STARTING}
     * @throws ServiceLifecycleException wenn der Start fehlschlägt
     */
    public ServiceInfo start() throws ServiceLifecycleException {
        crashes.policy().reset();
        return startInstance();
    }

    /**
     * Übernimmt einen Service, dessen Prozess einen Cloud-Neustart überlebt hat.
     *
     * <p>Die Frist bis {@code ONLINE} gilt auch hier: Meldet sich seine Bridge
     * nicht, wird der Prozess beendet und läuft durch die Crash-Kette — besser
     * als ein für immer hängender {@code STARTING}-Service, den niemand mehr
     * erreicht.
     *
     * @param lease Port-Lease des laufenden Prozesses
     * @param pid   Prozess-ID
     * @return der Service im Zustand {@code STARTING}
     * @throws ServiceLifecycleException wenn der Prozess nicht übernommen werden kann
     */
    public ServiceInfo adopt(PortLease lease, long pid) throws ServiceLifecycleException {
        long token = generation.incrementAndGet();
        ServiceInfo info = instance.adopt(lease, pid);
        scheduler.schedule(() -> {
            if (generation.get() == token) {
                instance.startTimedOut().ifPresent(this::onExit);
            }
        }, startTimeout);
        return info;
    }

    /**
     * Meldet die Bridge als bereit und plant den Versuchs-Reset.
     *
     * @return der Service im Zustand {@code ONLINE}
     */
    public ServiceInfo online() {
        ServiceInfo info = instance.online();
        long current = generation.incrementAndGet();
        scheduler.schedule(() -> {
            if (generation.get() == current && instance.info().getState() == ServiceState.ONLINE) {
                crashes.policy().reset();
            }
        }, onlineReset);
        return info;
    }

    /**
     * Stoppt den Service und verhindert einen noch ausstehenden Neustart.
     *
     * @param timeout Graceful-Timeout
     * @param force   {@code true} killt sofort
     * @return der Service im Zustand {@code STOPPED}
     * @throws ServiceLifecycleException wenn der Stop fehlschlägt
     */
    public ServiceInfo stop(Duration timeout, boolean force) throws ServiceLifecycleException {
        generation.incrementAndGet();
        return instance.stop(timeout, force);
    }

    /**
     * Meldet einen extern erkannten Exit, z. B. einen Heartbeat-Timeout.
     *
     * @param exit Exit mit Grund und Log-Tail
     */
    public void reportExit(ServiceExit exit) {
        onExit(Objects.requireNonNull(exit, "exit"));
    }

    /**
     * @return der überwachte Service
     */
    public ServiceInstance instance() {
        return instance;
    }

    /**
     * @return der Crash-Handler
     */
    public CrashHandler crashHandler() {
        return crashes;
    }

    private ServiceInfo startInstance() throws ServiceLifecycleException {
        long token = generation.incrementAndGet();
        ServiceInfo info = instance.start();
        scheduler.schedule(() -> {
            if (generation.get() == token) {
                instance.startTimedOut().ifPresent(this::onExit);
            }
        }, startTimeout);
        return info;
    }

    private void onExit(ServiceExit exit) {
        generation.incrementAndGet();
        crashes.crash(exit.info(), exit.directory(), exit.exitCode(), exit.reason(), exit.logTail());
    }

    private void restartIfStillCrashed() {
        if (instance.info().getState() != ServiceState.CRASHED) {
            return;
        }
        try {
            startInstance();
        } catch (ServiceLifecycleException e) {
            log.warn("Neustart von '{}' fehlgeschlagen: {}", instance.definition().name(), e.getMessage());
            degradedGroups.accept(instance.definition().group().getName());
        }
    }
}
