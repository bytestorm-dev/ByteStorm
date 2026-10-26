package dev.bytestorm.node.crash;

import dev.bytestorm.core.config.CrashSettings;
import dev.bytestorm.proto.ServiceCrashed;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Bündelt die Crash-Konfiguration, die jeder Service im Node teilt.
 *
 * @param settings  Versuche und Backoffs
 * @param reports   Berichts-Schreiber
 * @param events    Publisher für {@code ServiceCrashed}
 * @param scheduler Zeitplan für Backoff, Start-Timeout und Online-Reset
 * @param degraded  meldet eine erschöpfte Gruppe
 */
public record CrashSupport(
        CrashSettings settings,
        CrashReportWriter reports,
        Consumer<ServiceCrashed> events,
        CrashHandler.Scheduler scheduler,
        Consumer<String> degraded) {

    /**
     * @param settings  Restart-Konfiguration
     * @param reports   Berichte
     * @param events    Event-Publisher
     * @param scheduler Scheduler
     * @param degraded  Degraded-Callback
     */
    public CrashSupport {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(reports, "reports");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(degraded, "degraded");
    }
}
