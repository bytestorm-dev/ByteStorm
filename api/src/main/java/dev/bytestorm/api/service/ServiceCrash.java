package dev.bytestorm.api.service;

import java.util.Objects;

/**
 * Letzter Absturz eines Services (§12.5), so wie die API ihn ausliefert.
 *
 * <p>Der Zustand {@code CRASHED} sagt nicht, <em>warum</em> ein Service beendet
 * wurde. Dashboard, CLI und REST zeigen deshalb Ursache, Exit-Code und Zeitpunkt
 * mit an; der vollständige Bericht liegt unter {@link #report()}.
 *
 * @param service  Service-Name
 * @param reason   Ursache, z. B. {@code Start-Timeout}
 * @param exitCode Exit-Code, {@code -1} wenn der Node den Service selbst beendet hat
 * @param time     Zeitpunkt als Epochen-Millis
 * @param report   Pfad des Crash-Berichts, leer wenn keiner geschrieben wurde
 */
public record ServiceCrash(String service, String reason, int exitCode, long time, String report) {

    /**
     * @param service  Service-Name
     * @param reason   Ursache
     * @param exitCode Exit-Code
     * @param time     Zeitpunkt als Epochen-Millis
     * @param report   Pfad des Berichts, {@code null} wird zu leer
     */
    public ServiceCrash {
        Objects.requireNonNull(service, "service");
        reason = reason == null ? "" : reason;
        report = report == null ? "" : report;
    }
}
