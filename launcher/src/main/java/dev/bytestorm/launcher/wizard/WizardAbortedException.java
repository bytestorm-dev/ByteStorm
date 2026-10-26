package dev.bytestorm.launcher.wizard;

/**
 * Der Erststart wurde abgebrochen (Strg+C, EOF oder abgelehnter Wiederholungsversuch).
 *
 * <p>Eine abgebrochene Einrichtung schreibt keine Konfiguration.
 */
public class WizardAbortedException extends RuntimeException {

    /**
     * @param message Grund des Abbruchs
     */
    public WizardAbortedException(String message) {
        super(message);
    }
}
