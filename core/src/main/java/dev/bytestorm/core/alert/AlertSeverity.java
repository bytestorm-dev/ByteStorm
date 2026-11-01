package dev.bytestorm.core.alert;

/**
 * Dringlichkeit eines Alarms.
 *
 * <p>Nur zwei Stufen: {@link #WARNING} heißt „läuft noch, aber jemand sollte
 * hinsehen“, {@link #CRITICAL} heißt „etwas ist bereits kaputt“. Ein dritter
 * Zwischenschritt lädt nur dazu ein, ihn zu ignorieren.
 */
public enum AlertSeverity {

    /** Beeinträchtigt den Betrieb, ist aber nicht ausgefallen. */
    WARNING,

    /** Ausfall oder unmittelbar drohender Ausfall. */
    CRITICAL
}
