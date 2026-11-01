package dev.bytestorm.module.rest;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Anfragebegrenzung je API-Key (§11, §20).
 *
 * <p>Ein Token-Bucket je Key: Das Fass ist mit {@code n} Anfragen gefüllt und
 * läuft mit {@code n} pro Minute nach. Wer schneller anfragt, bekommt {@code 429}
 * samt {@code Retry-After} — statt die Cloud unbegrenzt zu belasten. Die Grenze
 * gilt je Key, damit ein eifriger Monitoring-Abruf die Administration nicht
 * aussperrt.
 *
 * <p>{@code n = 0} schaltet die Begrenzung ab.
 */
final class RateLimits {

    /** Aus: keine Begrenzung. */
    static final int OFF = 0;

    private final int perMinute;

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param perMinute erlaubte Anfragen je Minute und Key, {@value #OFF} für aus
     */
    RateLimits(int perMinute) {
        this.perMinute = Math.max(OFF, perMinute);
    }

    /**
     * @return {@code true}, wenn die Begrenzung greift
     */
    boolean enabled() {
        return perMinute > OFF;
    }

    /**
     * Entnimmt ein Token für den Key.
     *
     * @param key Name des Keys, nicht der Wert
     * @return {@code 0}, wenn die Anfrage laufen darf, sonst Sekunden bis zum nächsten Token
     */
    long retryAfter(String key) {
        if (!enabled()) {
            return 0;
        }
        return buckets.computeIfAbsent(key, name -> new Bucket(perMinute)).reserve();
    }

    /**
     * @return die eingestellte Grenze
     */
    int perMinute() {
        return perMinute;
    }

    /**
     * Fass für einen Key. {@link #reserve()} und die Nachfüllung laufen unter dem
     *selben Schloss, damit parallele Anfragen nie mehr Tokens bekommen als erlaubt.
     */
    private static final class Bucket {

        private final double capacity;

        private final double perSecond;

        private double tokens;

        private long lastNanos;

        Bucket(int perMinute) {
            this.capacity = perMinute;
            this.perSecond = perMinute / 60.0;
            this.tokens = perMinute;
            this.lastNanos = System.nanoTime();
        }

        synchronized long reserve() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - lastNanos) / 1_000_000_000.0 * perSecond);
            lastNanos = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - tokens) / perSecond));
        }
    }
}
