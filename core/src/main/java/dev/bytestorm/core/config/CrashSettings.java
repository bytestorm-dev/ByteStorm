package dev.bytestorm.core.config;

import java.util.List;

/**
 * Auto-Restart-Verhalten nach einem Crash (§12.5, §21).
 *
 * @param maxRestarts     Abstürze in Folge, nach denen nicht mehr automatisch
 *                        gestartet wird; die Gruppe gilt dann als {@code DEGRADED}
 * @param backoffSeconds  Wartezeit je Versuch in Sekunden
 */
public record CrashSettings(int maxRestarts, List<Integer> backoffSeconds) {

    /** Default: nach drei Abstürzen in Folge startet der Service nicht mehr von selbst. */
    public static final int DEFAULT_MAX_RESTARTS = 3;

    /** Default-Backoff, passend zu den Versuchen. */
    public static final List<Integer> DEFAULT_BACKOFF_SECONDS = List.of(5, 15, 60);

    /**
     * @param maxRestarts    Versuche, {@code 0} nutzt den Default
     * @param backoffSeconds Backoff, {@code null}/leer nutzt den Default
     */
    public CrashSettings {
        maxRestarts = maxRestarts == 0 ? DEFAULT_MAX_RESTARTS : maxRestarts;
        backoffSeconds = backoffSeconds == null || backoffSeconds.isEmpty()
                ? DEFAULT_BACKOFF_SECONDS
                : List.copyOf(backoffSeconds);
    }

    /**
     * @param attempt Versuch, beginnend bei 1
     * @return Wartezeit für den Versuch; der letzte Wert wird wiederholt
     */
    public int backoffFor(int attempt) {
        if (backoffSeconds.isEmpty()) {
            return 0;
        }
        int index = Math.min(Math.max(attempt, 1) - 1, backoffSeconds.size() - 1);
        return backoffSeconds.get(index);
    }
}
