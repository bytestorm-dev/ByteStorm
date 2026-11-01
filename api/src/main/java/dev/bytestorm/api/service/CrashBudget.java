package dev.bytestorm.api.service;

/**
 * Verbleibendes Absturz-Budget eines Services (§12.5), wie es Dashboard, CLI und
 * REST anzeigen.
 *
 * <p>Der Zustand eines Services verrät nicht, wie nah er an der
 * {@code DEGRADED}-Sperre steht. Deshalb liefert die API zusätzlich
 * {@code remaining/total}: {@code 0} heißt, der nächste Absturz beendet die
 * automatischen Neustarts.
 *
 * @param remaining verbleibende Abstürze
 * @param total     Budget eines frisch gestarteten Services, {@code 0} wenn unbekannt
 */
public record CrashBudget(int remaining, int total) {

    /**
     * @return {@code remaining/total}, oder {@code -} wenn kein Budget bekannt ist
     */
    public String format() {
        return total <= 0 ? "-" : remaining + "/" + total;
    }
}
