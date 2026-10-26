package dev.bytestorm.node.crash;

import dev.bytestorm.core.config.CrashSettings;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Entscheidet, ob ein Service nach einem Crash neu gestartet wird (§12.5).
 *
 * <p>Die Versuche und Backoffs kommen aus {@link CrashSettings} (Default 3
 * Versuche, 5/15/60 s). Nach einer definierten Online-Zeit werden die Versuche
 * zurückgesetzt, damit eine Gruppe nicht nach seltenen Einzelcrashs dauerhaft
 * als erschöpft gilt.
 */
public final class RestartPolicy {

    /** Online-Zeit, nach der der Versuchszähler zurückgesetzt wird. */
    public static final Duration DEFAULT_ONLINE_RESET = Duration.ofMinutes(5);

    private final CrashSettings settings;
    private final Duration onlineReset;
    private int attempts;

    /**
     * @param settings Restart-Konfiguration
     */
    public RestartPolicy(CrashSettings settings) {
        this(settings, DEFAULT_ONLINE_RESET);
    }

    /**
     * @param settings     Restart-Konfiguration
     * @param onlineReset  Online-Zeit bis zum Reset
     */
    public RestartPolicy(CrashSettings settings, Duration onlineReset) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.onlineReset = Objects.requireNonNull(onlineReset, "onlineReset");
        if (onlineReset.isNegative() || onlineReset.isZero()) {
            throw new IllegalArgumentException("onlineReset muss positiv sein: " + onlineReset);
        }
    }

    /**
     * Vermerkt einen Crash und liefert die Wartezeit bis zum Neustart.
     *
     * @return Backoff oder leer, wenn keine Versuche mehr übrig sind
     */
    public synchronized Optional<Duration> nextBackoff() {
        if (attempts >= settings.maxRestarts()) {
            return Optional.empty();
        }
        attempts++;
        return Optional.of(Duration.ofSeconds(settings.backoffFor(attempts)));
    }

    /**
     * @return {@code true}, wenn alle Versuche verbraucht sind
     */
    public synchronized boolean exhausted() {
        return attempts >= settings.maxRestarts();
    }

    /**
     * @return bisherige Versuche
     */
    public synchronized int attempts() {
        return attempts;
    }

    /**
     * Setzt die Versuche zurück, wenn der Service lange genug lief.
     *
     * @param online Laufzeit seit dem letzten Start
     * @return {@code true}, wenn zurückgesetzt wurde
     */
    public synchronized boolean resetIfOnlineFor(Duration online) {
        Objects.requireNonNull(online, "online");
        if (online.compareTo(onlineReset) >= 0 && attempts > 0) {
            attempts = 0;
            return true;
        }
        return false;
    }

    /**
     * Setzt die Versuche sofort zurück, z. B. nach einem manuellen Neustart.
     */
    public synchronized void reset() {
        attempts = 0;
    }
}
