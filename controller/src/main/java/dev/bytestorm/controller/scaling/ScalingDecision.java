package dev.bytestorm.controller.scaling;

/**
 * Eine Entscheidung des {@link ScalingManager} für eine Gruppe (§11).
 *
 * <p>Entscheidungen entstehen sowohl bei echten Läufen ({@link ScalingManager#tick()})
 * als auch im Dry-Run ({@link ScalingManager#explain()}). Im Dry-Run werden sie
 * nur berichtet und nicht ausgeführt.
 *
 * @param group   Gruppenname
 * @param action  geplante oder ausgeführte Aktion
 * @param rule    auslösende Regel bzw. Blockade
 * @param current aktuelle Anzahl aktiver Services
 * @param desired Zielanzahl nach der Aktion
 * @param reason  Begründung mit Messwerten
 */
public record ScalingDecision(
        String group,
        Action action,
        String rule,
        int current,
        int desired,
        String reason) {

    /**
     * Art der Entscheidung.
     */
    public enum Action {
        /** Ein Service soll gestartet werden. */
        START,
        /** Ein Service soll gestoppt werden. */
        STOP,
        /** Es wurde bewusst nicht skaliert. */
        NONE
    }
}
