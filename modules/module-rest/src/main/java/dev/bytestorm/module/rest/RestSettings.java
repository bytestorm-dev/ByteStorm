package dev.bytestorm.module.rest;

import java.util.Map;
import java.util.Objects;

/**
 * Einstellungen des REST-Moduls (§11). Sie kommen aus der Modul-Konfiguration
 * ({@code modules_config} in MongoDB), die der {@code ModuleContext} als
 * schreibgeschützte Map reicht.
 *
 * <p>Der API-Key ist Pflicht: Ohne ihn würde das Modul die Cloud ohne
 * Authentifizierung erreichbar machen. Fehlt er oder ist er zu kurz, verweigert
 * das Modul schon in {@code onLoad} den Dienst, statt ungeschützt zu lauschen
 * (§11, §20).
 *
 * @param host   Netzadresse, auf der gelauscht wird
 * @param port   Port, auf dem gelauscht wird
 * @param apiKey Key, den Clients mitschicken müssen
 */
record RestSettings(String host, int port, String apiKey) {

    /** Standard-Port des REST-Moduls. */
    static final int DEFAULT_PORT = 8080;

    /**
     * Standard-Adresse: nur lokal. Der Key schützt die API, ersetzt aber kein TLS —
     * für den Zugriff von außen gehört ein Reverse-Proxy davor (§20).
     */
    static final String DEFAULT_HOST = "127.0.0.1";

    /** Kürzere Keys gelten als unsicher. */
    static final int MIN_KEY_LENGTH = 8;

    /**
     * @param host   Netzadresse
     * @param port   Port zwischen 1 und 65535
     * @param apiKey Key mit mindestens {@value #MIN_KEY_LENGTH} Zeichen
     */
    RestSettings {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(apiKey, "apiKey");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port liegt außerhalb von 1-65535: " + port);
        }
    }

    /**
     * @param settings Einstellungen aus der Modul-Konfiguration, z. B. {@code port}, {@code apiKey}
     * @return die geprüften Einstellungen
     * @throws IllegalArgumentException wenn ein Wert unbrauchbar ist oder der Key fehlt
     */
    static RestSettings from(Map<String, String> settings) {
        Objects.requireNonNull(settings, "settings");
        String apiKey = value(settings, "apiKey", "");
        if (apiKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalArgumentException("apiKey fehlt oder ist kürzer als " + MIN_KEY_LENGTH
                    + " Zeichen — Einstellung 'apiKey' des Moduls 'rest' setzen");
        }
        return new RestSettings(value(settings, "host", DEFAULT_HOST), port(settings), apiKey);
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

    private static String value(Map<String, String> settings, String key, String fallback) {
        String value = settings.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * @return die Adresse, unter der das Modul erreichbar ist, ohne Key
     */
    @Override
    public String toString() {
        return host + ":" + port;
    }
}
