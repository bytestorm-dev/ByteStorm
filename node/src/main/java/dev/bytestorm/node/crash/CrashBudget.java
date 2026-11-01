package dev.bytestorm.node.crash;

/**
 * Wie viele Abstürze ein Service noch verkraftet, bevor er nicht mehr
 * automatisch startet (§12.5).
 *
 * <p>Der Zustand eines Services verrät nicht, wie nah er an der
 * {@code DEGRADED}-Sperre steht: Ein Service mit zwei Abstürzen ist genauso
 * {@code ONLINE} wie ein frisch gestarteter. Das Dashboard zeigt deshalb
 * {@code verbleibend/gesamt}.
 *
 * @param remaining verbleibende Abstürze; {@code 0} heißt, der nächste Absturz
 *                  beendet die automatischen Neustarts
 * @param total     Budget eines frisch gestarteten Services
 */
public record CrashBudget(int remaining, int total) {

    /**
     * @param remaining verbleibende Abstürze
     * @param total     Gesamtbudget
     */
    public CrashBudget {
        if (total < 1) {
            throw new IllegalArgumentException("Budget muss positiv sein: " + total);
        }
        if (remaining < 0) {
            throw new IllegalArgumentException("Verbleibende Abstürze dürfen nicht negativ sein: " + remaining);
        }
        remaining = Math.min(remaining, total);
    }

    /**
     * @param policy Versuchsverwaltung eines Services
     * @return der Stand dieses Services
     */
    public static CrashBudget of(RestartPolicy policy) {
        int total = policy.settings().maxRestarts();
        return new CrashBudget(Math.max(0, total - policy.attempts()), total);
    }

    /**
     * @return {@code true}, wenn der nächste Absturz den Service nicht mehr startet
     */
    public boolean exhausted() {
        return remaining == 0;
    }
}
