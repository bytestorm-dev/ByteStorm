package dev.bytestorm.launcher.alert;

import dev.bytestorm.core.alert.Alert;
import dev.bytestorm.core.alert.AlertService;
import dev.bytestorm.core.health.ComponentHealth;
import dev.bytestorm.core.health.HealthReport;
import dev.bytestorm.core.health.Healthcheck;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Prüft die Cloud regelmäßig und übersetzt die Lage in Alarme (§20).
 *
 * <p>Geprüft werden die Dinge, die im Betrieb tatsächlich still kaputtgehen:
 * <ul>
 *   <li>MongoDB, Redis oder die Runtime antworten nicht (aus
 *       {@link Healthcheck}, also derselbe Befund wie {@code /health}),</li>
 *   <li>eine Gruppe steht auf {@code DEGRADED} — die Skalierung hat die
 *       Neustarts eingestellt, ohne dass es jemand merkt,</li>
 *   <li>ein Service ist abgestürzt und wartet auf den nächsten Versuch,</li>
 *   <li>der freie Speicher fällt unter {@link #DEFAULT_MIN_FREE_BYTES}.</li>
 * </ul>
 *
 * <p>Alarme werden über {@link AlertService} entprellt: Solange die Lage gleich
 * bleibt, entsteht keine neue Zeile. {@code doctor} bleibt die ausführliche
 * Momentaufnahme; dieser Monitor ist die laufende Überwachung.
 *
 * <p>Bewusst nicht geprüft: Heartbeat-Timeouts und die REST-Fehlerrate. Beide
 * brauchen Zähler über Zeit, die es noch nicht gibt — sie stehen in
 * {@code prod-ready-todo.md}.
 */
public final class AlertMonitor implements AutoCloseable {

    /** Abstand zwischen zwei Prüfläufen. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(30);

    /** Unterhalb dieser Grenze wird der Speicher knapp: 5 GiB. */
    public static final long DEFAULT_MIN_FREE_BYTES = 5L * 1024 * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(AlertMonitor.class);

    private final Healthcheck healthcheck;
    private final Supplier<List<ServiceGroup>> groups;
    private final Supplier<List<ServiceInfo>> services;
    private final Predicate<String> degraded;
    private final Path root;
    private final AlertService alerts;
    private final Duration interval;
    private final long minFreeBytes;

    private ScheduledExecutorService scheduler;
    private boolean started;

    /**
     * @param healthcheck Fassade aus Mongo, Redis und Runtime
     * @param groups      liefert alle Gruppen
     * @param services    liefert alle registrierten Services
     * @param degraded    sagt, ob eine Gruppe ihre Neustarts aufgebraucht hat
     * @param root        Cloud-Verzeichnis, dessen Dateisystem beobachtet wird
     * @param alerts      Ziel und Gedächtnis der Alarme
     */
    public AlertMonitor(Healthcheck healthcheck,
                        Supplier<List<ServiceGroup>> groups,
                        Supplier<List<ServiceInfo>> services,
                        Predicate<String> degraded,
                        Path root,
                        AlertService alerts) {
        this(healthcheck, groups, services, degraded, root, alerts,
                DEFAULT_INTERVAL, DEFAULT_MIN_FREE_BYTES);
    }

    AlertMonitor(Healthcheck healthcheck,
                 Supplier<List<ServiceGroup>> groups,
                 Supplier<List<ServiceInfo>> services,
                 Predicate<String> degraded,
                 Path root,
                 AlertService alerts,
                 Duration interval,
                 long minFreeBytes) {
        this.healthcheck = Objects.requireNonNull(healthcheck, "healthcheck");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.services = Objects.requireNonNull(services, "services");
        this.degraded = Objects.requireNonNull(degraded, "degraded");
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval muss positiv sein: " + interval);
        }
        if (minFreeBytes < 0) {
            throw new IllegalArgumentException("minFreeBytes darf nicht negativ sein: " + minFreeBytes);
        }
        this.minFreeBytes = minFreeBytes;
    }

    /**
     * Startet die wiederkehrende Prüfung auf einem Virtual Thread. Der erste
     * Lauf beginnt sofort, damit ein kaputter Datenbestand nicht erst nach
     * einem Intervall auffällt. Wiederholte Aufrufe sind idempotent.
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(
                runnable -> Thread.ofVirtual().name("bytestorm-alerts", 0).unstarted(runnable));
        long seconds = Math.max(1, interval.toSeconds());
        scheduler.scheduleWithFixedDelay(this::tick, 0, seconds, TimeUnit.SECONDS);
        started = true;
        log.info("Alarmüberwachung gestartet (Intervall {} s, Speicherschwelle {} GiB)",
                seconds, minFreeBytes / (1024L * 1024 * 1024));
    }

    /**
     * Führt einen Prüflauf aus. Fehler beim Prüfen werden geloggt, nicht
     * geworfen: Ein einzelner kaputter Bestandteil darf die Überwachung nicht
     * dauerhaft beenden.
     */
    public void tick() {
        try {
            HealthReport report = healthcheck.check();
            AlertService.Changes changes = alerts.evaluate(signals(report, groups.get(),
                    services.get(), degraded, usableBytes(), minFreeBytes));
            if (!changes.isEmpty()) {
                log.info("Alarmprüfung: {} neu, {} behoben", changes.raised().size(),
                        changes.resolved().size());
            }
        } catch (RuntimeException e) {
            log.warn("Alarmprüfung fehlgeschlagen: {}", e.getMessage(), e);
        }
    }

    @Override
    public synchronized void close() {
        started = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /**
     * Übersetzt einen Befund in Alarme. Reine Funktion ohne Seiteneffekte,
     * damit die Regeln ohne Scheduler und ohne Cloud testbar bleiben.
     *
     * @param report       Ergebnis des Healthchecks
     * @param groups       alle Gruppen
     * @param services     alle Services
     * @param degraded     sagt, ob eine Gruppe auf {@code DEGRADED} steht
     * @param usableBytes  freier Speicher, {@code -1} wenn unbekannt
     * @param minFreeBytes Schwelle; {@code 0} schaltet die Speicherprüfung ab
     * @return alle gerade gültigen Alarme
     */
    static List<Alert> signals(HealthReport report,
                               List<ServiceGroup> groups,
                               List<ServiceInfo> services,
                               Predicate<String> degraded,
                               long usableBytes,
                               long minFreeBytes) {
        List<Alert> found = new ArrayList<>();

        for (ComponentHealth component : report.unhealthy()) {
            found.add(Alert.critical("health:" + component.name(),
                    name(component.name()) + " ist nicht erreichbar: " + component.detail()));
        }

        for (ServiceGroup group : groups == null ? List.<ServiceGroup>of() : groups) {
            if (!degraded.test(group.getName())) {
                continue;
            }
            found.add(Alert.critical("group:" + group.getName(),
                    "Gruppe '" + group.getName() + "' ist DEGRADED — automatische Neustarts sind"
                            + " gestoppt. 'service start " + group.getName()
                            + "' startet sie wieder und hebt die Sperre auf."));
        }

        for (ServiceInfo service : services == null ? List.<ServiceInfo>of() : services) {
            if (service.getState() != ServiceState.CRASHED) {
                continue;
            }
            found.add(Alert.warning("service:" + service.getName(),
                    "Service '" + service.getName() + "' (Gruppe '" + service.getGroup()
                            + "') ist abgestürzt; der nächste Neustart läuft nach der"
                            + " Neustart-Policy. Logs unter logs/" + service.getName() + "/."));
        }

        if (usableBytes >= 0 && minFreeBytes > 0 && usableBytes < minFreeBytes) {
            found.add(Alert.critical("disk", String.format(Locale.ROOT,
                    "Nur %d GiB freier Speicher (Schwelle %d GiB) — Logs und Crash-Berichte"
                            + " aufräumen oder Volume vergrößern",
                    usableBytes / (1024L * 1024 * 1024), minFreeBytes / (1024L * 1024 * 1024))));
        }
        return List.copyOf(found);
    }

    private long usableBytes() {
        try {
            return Files.getFileStore(root).getUsableSpace();
        } catch (IOException e) {
            log.debug("Freier Speicher für {} nicht lesbar: {}", root, e.getMessage());
            return -1;
        }
    }

    private static String name(String component) {
        return switch (component) {
            case Healthcheck.MONGO -> "MongoDB";
            case Healthcheck.REDIS -> "Redis";
            case Healthcheck.RUNTIME -> "Runtime";
            default -> component;
        };
    }
}
