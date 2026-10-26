package dev.bytestorm.core.health;

/**
 * Prüfung eines einzelnen Bestandteils der Cloud.
 *
 * <p>Eine Prüfung gilt als erfolgreich, wenn sie ohne Exception zurückkehrt.
 * Die Meldung der Exception landet als Detail im {@link HealthReport}; sie darf
 * deshalb keine Secrets enthalten (§20).
 */
@FunctionalInterface
public interface HealthProbe {

    /**
     * Prüft die Verfügbarkeit.
     *
     * @throws Exception wenn der Bestandteil nicht verfügbar ist
     */
    void check() throws Exception;
}
