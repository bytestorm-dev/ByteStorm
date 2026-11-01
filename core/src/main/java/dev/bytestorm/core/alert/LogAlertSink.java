package dev.bytestorm.core.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schreibt Alarme ins Cloud-Log ({@code logs/cloud.log} und Konsole).
 *
 * <p>Das ist das Ziel, das immer funktioniert: Wer die Cloud startet, sieht
 * Alarme ohne weitere Einrichtung im Terminal und findet sie später in der
 * rotierenden Logdatei wieder.
 */
public final class LogAlertSink implements AlertSink {

    private static final Logger log = LoggerFactory.getLogger("dev.bytestorm.alert");

    @Override
    public void raised(Alert alert) {
        if (alert.severity() == AlertSeverity.CRITICAL) {
            log.error("ALARM [{}] {}", alert.source(), alert.message());
        } else {
            log.warn("ALARM [{}] {}", alert.source(), alert.message());
        }
    }

    @Override
    public void resolved(Alert alert) {
        log.info("Alarm behoben [{}] {}", alert.source(), alert.message());
    }
}
