package dev.bytestorm.node.crash;

import dev.bytestorm.proto.ServiceCrashed;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Letzter Absturz eines Services (§12.5).
 *
 * <p>Der {@link ServiceManager} hält je Service die zuletzt gemeldete Ursache
 * fest, damit Dashboard und CLI zeigen können, <em>warum</em> ein Service in
 * {@code CRASHED} steht — der Zustand allein verrät das nicht. Der vollständige
 * Bericht liegt weiterhin unter {@link #report()}.
 *
 * @param service  Service-Name
 * @param reason   Ursache, z. B. {@code Start-Timeout} oder {@code unerwarteter Exit}
 * @param exitCode Exit-Code, {@code -1} wenn der Node den Service selbst beendet hat
 * @param time     Zeitpunkt des Absturzes
 * @param report   Ordner des Crash-Berichts
 */
public record CrashInfo(String service, String reason, int exitCode, Instant time, Path report) {

    /**
     * @param service  Service-Name
     * @param reason   Ursache
     * @param exitCode Exit-Code
     * @param time     Zeitpunkt
     * @param report   Berichtsordner
     */
    public CrashInfo {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(report, "report");
    }

    /**
     * @param event Ereignis aus dem Crash-Pfad
     * @return die Zusammenfassung für Anzeige und CLI
     */
    public static CrashInfo of(ServiceCrashed event) {
        Objects.requireNonNull(event, "event");
        return new CrashInfo(
                event.getService().getName(),
                event.getReason(),
                event.getExitCode(),
                Instant.ofEpochMilli(event.getTime()),
                Path.of(event.getReportPath()));
    }
}
