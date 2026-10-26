package dev.bytestorm.node.crash;

import dev.bytestorm.core.config.CrashSettings;
import dev.bytestorm.proto.ServiceCrashed;
import dev.bytestorm.proto.ServiceInfo;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Reagiert auf einen Service-Crash (§12.5): schreibt den Bericht, publiziert
 * {@link ServiceCrashed}, startet nach Backoff neu oder markiert die Gruppe als
 * {@code DEGRADED}, wenn die Versuche erschöpft sind.
 *
 * <p>Der Handler kennt weder Runtime noch Lifecycle; Neustart und
 * Gruppen-Markierung laufen über {@link Actions}, der Zeitplan über
 * {@link Scheduler}. Damit ist die komplette Abfolge ohne echte Prozesse
 * testbar.
 */
public final class CrashHandler {

    /** Ergebnis der Crash-Behandlung. */
    public enum Outcome {
        /** Ein Neustart ist eingeplant. */
        RESTART_SCHEDULED,
        /** Keine Versuche mehr; die Gruppe wurde als degradiert markiert. */
        DEGRADED
    }

    /**
     * Plant eine Aufgabe mit Verzögerung.
     */
    @FunctionalInterface
    public interface Scheduler {

        /**
         * @param task  Aufgabe
         * @param delay Verzögerung
         */
        void schedule(Runnable task, Duration delay);
    }

    /**
     * Aktionen des Lifecycles.
     */
    public interface Actions {

        /**
         * @param service Service, der neu gestartet werden soll
         */
        void restart(ServiceInfo service);

        /**
         * @param group Gruppe, die keine weiteren Neustarts mehr bekommt
         */
        void markDegraded(String group);
    }

    private final RestartPolicy policy;
    private final CrashReportWriter reports;
    private final Consumer<ServiceCrashed> events;
    private final Scheduler scheduler;
    private final Actions actions;
    private final Clock clock;

    /**
     * @param settings  Restart-Konfiguration
     * @param reports   Berichts-Schreiber
     * @param events    Event-Publisher, z. B. {@code EventBus::publish}
     * @param scheduler Zeitplan
     * @param actions   Neustart und Degraded-Markierung
     */
    public CrashHandler(CrashSettings settings,
                        CrashReportWriter reports,
                        Consumer<ServiceCrashed> events,
                        Scheduler scheduler,
                        Actions actions) {
        this(new RestartPolicy(settings), reports, events, scheduler, actions, Clock.systemUTC());
    }

    CrashHandler(RestartPolicy policy,
                 CrashReportWriter reports,
                 Consumer<ServiceCrashed> events,
                 Scheduler scheduler,
                 Actions actions,
                 Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.events = Objects.requireNonNull(events, "events");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Behandelt einen Crash.
     *
     * @param service          Service zum Crash-Zeitpunkt
     * @param serviceDirectory Ordner des Services
     * @param exitCode         Exit-Code
     * @param reason           Ursache
     * @param logTail          letzte Logzeilen, älteste zuerst
     * @return Ergebnis der Behandlung
     */
    public Outcome crash(ServiceInfo service, Path serviceDirectory, int exitCode, String reason, List<String> logTail) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(serviceDirectory, "serviceDirectory");
        List<String> tail = logTail == null ? List.of() : List.copyOf(logTail);

        Path report = reports.write(service, serviceDirectory, exitCode, reason, tail);
        events.accept(ServiceCrashed.newBuilder()
                .setService(service)
                .setExitCode(exitCode)
                .setReason(reason)
                .setTime(clock.millis())
                .addAllLogTail(tail)
                .setReportPath(report.toString())
                .build());

        Optional<Duration> backoff = policy.nextBackoff();
        if (backoff.isEmpty()) {
            actions.markDegraded(service.getGroup());
            return Outcome.DEGRADED;
        }
        scheduler.schedule(() -> actions.restart(service), backoff.get());
        return Outcome.RESTART_SCHEDULED;
    }

    /**
     * Setzt die Versuche zurück, wenn der Service lange genug lief.
     *
     * @param online Laufzeit seit dem letzten Start
     * @return {@code true}, wenn zurückgesetzt wurde
     */
    public boolean markOnline(Duration online) {
        return policy.resetIfOnlineFor(online);
    }

    /**
     * @return die Versuchsverwaltung
     */
    public RestartPolicy policy() {
        return policy;
    }

    /**
     * @return Scheduler auf Virtual Threads
     */
    public static Scheduler virtualThreads() {
        return (task, delay) -> Thread.ofVirtual().name("bytestorm-crash-restart").start(() -> {
            try {
                Thread.sleep(delay.toMillis());
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
