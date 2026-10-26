package dev.bytestorm.core.config;

/**
 * Intervall des ScalingManager (§11, §21). Empfohlen sind 2–5 Sekunden.
 *
 * @param intervalSeconds Intervall zwischen zwei Scaling-Läufen
 */
public record ScalingSettings(int intervalSeconds) {

    /** Default-Intervall. */
    public static final int DEFAULT_INTERVAL_SECONDS = 3;

    /**
     * @param intervalSeconds Intervall, {@code 0} nutzt den Default
     */
    public ScalingSettings {
        intervalSeconds = intervalSeconds == 0 ? DEFAULT_INTERVAL_SECONDS : intervalSeconds;
    }
}
