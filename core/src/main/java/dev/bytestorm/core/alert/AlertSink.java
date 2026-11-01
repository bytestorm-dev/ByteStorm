package dev.bytestorm.core.alert;

/**
 * Ziel für Alarme: Log, Datei, später ein Modul (Discord, Webhook).
 *
 * <p>Ein {@link AlertService} ruft {@link #raised} genau einmal beim Aktivieren
 * und {@link #resolved} genau einmal beim Abklingen. Implementierungen dürfen
 * werfen — der Service fängt es ab, damit ein kaputtes Ziel die übrigen nicht
 * mitnimmt.
 */
public interface AlertSink {

    /**
     * @param alert neu aktiver Alarm
     */
    void raised(Alert alert);

    /**
     * Der Alarm gilt nicht mehr. Standard: nichts tun, etwa für Ziele, die nur
     * einmalige Meldungen verschicken.
     *
     * @param alert abgeklungener Alarm
     */
    default void resolved(Alert alert) {
    }
}
