package dev.bytestorm.module.rest;

import java.util.Map;
import java.util.Objects;

/**
 * Einstellungen des REST-Moduls (§11). Sie kommen aus der Modul-Konfiguration
 * ({@code modules_config} in MongoDB), die der {@code ModuleContext} als
 * schreibgeschützte Map reicht.
 *
 * <p>Mindestens ein API-Key ist Pflicht: Ohne ihn würde das Modul die Cloud ohne
 * Authentifizierung erreichbar machen. Fehlt er oder ist er zu kurz, verweigert
 * das Modul schon in {@code onLoad} den Dienst, statt ungeschützt zu lauschen
 * (§11, §20). Welche Einstellungen es gibt und wie Scopes aufgebaut sind, steht
 * in {@link ApiKeys}; kurz:
 *
 * <pre>{@code
 * host               = 127.0.0.1
 * port               = 8080
 * apiKey             = <wert>              // voller Zugriff
 * apiKey.monitoring  = <wert>              // eigener Key je Integration
 * scopes.monitoring  = monitoring.read
 * rateLimit          = 120                 // Anfragen je Minute und Key, 0 = aus
 * }</pre>
 *
 * @param host              Netzadresse, auf der gelauscht wird
 * @param port              Port, auf dem gelauscht wird
 * @param keys              geprüfte API-Keys samt Scopes
 * @param requestsPerMinute Anfragen je Minute und Key, {@code 0} für unbegrenzt
 */
record RestSettings(String host, int port, ApiKeys keys, int requestsPerMinute) {

    /** Standard-Port des REST-Moduls. */
    static final int DEFAULT_PORT = 8080;

    /**
     * Standard-Adresse: nur lokal. Der Key schützt die API, ersetzt aber kein TLS —
     * für den Zugriff von außen gehört ein Reverse-Proxy davor (§20).
     */
    static final String DEFAULT_HOST = "127.0.0.1";

    /** Kürzere Keys gelten als unsicher. */
    static final int MIN_KEY_LENGTH = 8;

    /** Ohne Angabe gilt diese Grenze je Key und Minute. */
    static final int DEFAULT_RATE_LIMIT = 120;

    /** Obergrenze, damit ein Tippfehler die Grenze nicht faktisch abschaltet. */
    static final int MAX_RATE_LIMIT = 100_000;

    /**
     * @param host              Netzadresse
     * @param port              Port zwischen 1 und 65535
     * @param keys              mindestens ein Key
     * @param requestsPerMinute Anfragen je Minute und Key, {@code 0} für aus
     */
    RestSettings {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(keys, "keys");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port liegt außerhalb von 1-65535: " + port);
        }
        if (keys.size() < 1) {
            throw new IllegalArgumentException("mindestens ein API-Key ist Pflicht");
        }
        if (requestsPerMinute < RateLimits.OFF || requestsPerMinute > MAX_RATE_LIMIT) {
            throw new IllegalArgumentException("rateLimit muss zwischen " + RateLimits.OFF
                    + " und " + MAX_RATE_LIMIT + " liegen: " + requestsPerMinute);
        }
    }

    /**
     * @param settings Einstellungen aus der Modul-Konfiguration, z. B. {@code port}, {@code apiKey}
     * @return die geprüften Einstellungen
     * @throws IllegalArgumentException wenn ein Wert unbrauchbar ist oder kein Key gesetzt ist
     */
    static RestSettings from(Map<String, String> settings) {
        Objects.requireNonNull(settings, "settings");
        return new RestSettings(value(settings, "host", DEFAULT_HOST), port(settings),
                ApiKeys.from(settings), rateLimit(settings));
    }

    private static int port(Map<String, String> settings) {
        String raw = value(settings, "port", "");
        if (raw.isEmpty()) {
            return DEFAULT_PORT;
        }
        int port;
        try {
            port = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("port ist keine Zahl: " + raw, e);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port liegt außerhalb von 1-65535: " + raw);
        }
        return port;
    }

    private static int rateLimit(Map<String, String> settings) {
        String raw = value(settings, "rateLimit", "");
        if (raw.isEmpty()) {
            return DEFAULT_RATE_LIMIT;
        }
        int limit;
        try {
            limit = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("rateLimit ist keine Zahl: " + raw, e);
        }
        if (limit < RateLimits.OFF || limit > MAX_RATE_LIMIT) {
            throw new IllegalArgumentException("rateLimit muss zwischen " + RateLimits.OFF
                    + " und " + MAX_RATE_LIMIT + " liegen: " + raw);
        }
        return limit;
    }

    private static String value(Map<String, String> settings, String key, String fallback) {
        String value = settings.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * @return die Adresse, unter der das Modul erreichbar ist, ohne Keys
     */
    @Override
    public String toString() {
        return host + ":" + port;
    }
}
