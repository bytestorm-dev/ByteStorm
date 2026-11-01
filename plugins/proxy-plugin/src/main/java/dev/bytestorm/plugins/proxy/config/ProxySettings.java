package dev.bytestorm.plugins.proxy.config;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Einstellungen des Proxy-Plugins (§16).
 *
 * <p>Die Datei schaltet nur Funktionen. <em>Jeder</em> sichtbare Text steht in
 * den Sprachdateien unter {@code messages/} — so bleibt dieselbe Konfiguration
 * für jede Sprache gültig, und Übersetzer müssen keine Schalter anfassen.
 *
 * @param language    Sprachcode der Sprachdatei, z. B. {@code de}
 * @param tablist     automatische Tablist
 * @param maintenance Wartungsmodus
 * @param cloud       Anbindung an das REST-Modul der Cloud für {@code /cloud}
 */
public record ProxySettings(String language, Tablist tablist, Maintenance maintenance, Cloud cloud) {

    /** Sprache, die immer mitgeliefert wird und als Rückfallebene dient. */
    public static final String DEFAULT_LANGUAGE = "en";

    private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z]{2,3}(?:_[A-Z]{2})?");

    /**
     * @param language    Sprachcode
     * @param tablist     Tablist
     * @param maintenance Wartungsmodus
     * @param cloud       Cloud-Anbindung
     */
    public ProxySettings {
        language = language == null || language.isBlank() ? DEFAULT_LANGUAGE : language.trim();
        if (!LANGUAGE_CODE.matcher(language).matches()) {
            throw new IllegalArgumentException("language ist kein Sprachcode (z. B. 'de' oder 'pt_BR'): "
                    + language);
        }
        tablist = Objects.requireNonNull(tablist, "tablist");
        maintenance = Objects.requireNonNull(maintenance, "maintenance");
        cloud = Objects.requireNonNull(cloud, "cloud");
    }

    /**
     * @return Einstellungen für den ersten Start: Tablist an, Wartung aus, {@code /cloud} aus
     */
    public static ProxySettings defaults() {
        return new ProxySettings(DEFAULT_LANGUAGE, Tablist.defaults(), Maintenance.defaults(), Cloud.defaults());
    }

    /**
     * @param enabled       Tablist schreiben
     * @param updateSeconds Abstand zwischen zwei Aktualisierungen in Sekunden
     * @param permission    Permission, die {@code /cloud tablist} umschalten darf
     */
    public record Tablist(boolean enabled, int updateSeconds, String permission) {

        /** Default-Abstand, kurz genug für Spielerzahlen, lang genug für Ruhe. */
        public static final int DEFAULT_UPDATE_SECONDS = 5;

        /**
         * Eigene Permission für den Schalter. Sie ist bewusst getrennt von
         * {@code cloud.permission}: Wer die Tablist umschalten darf, muss nicht
         * auch Services stoppen oder Gruppen skalieren können.
         */
        public static final String DEFAULT_PERMISSION = "bytestorm.tablist.admin";

        /**
         * @param enabled       Tablist schreiben
         * @param updateSeconds Abstand in Sekunden, mindestens 1
         * @param permission    Permission, leer nutzt den Default
         */
        public Tablist {
            if (updateSeconds < 1) {
                throw new IllegalArgumentException(
                        "tablist.updateSeconds muss mindestens 1 sein: " + updateSeconds);
            }
            permission = permission == null || permission.isBlank()
                    ? DEFAULT_PERMISSION
                    : permission.trim();
        }

        /**
         * @return Tablist an, Aktualisierung alle {@value #DEFAULT_UPDATE_SECONDS} Sekunden,
         *         Default-Permission
         */
        public static Tablist defaults() {
            return new Tablist(true, DEFAULT_UPDATE_SECONDS, DEFAULT_PERMISSION);
        }
    }

    /**
     * @param enabled          Wartungsmodus aktiv
     * @param bypassPermission Permission, die den Login trotzdem erlaubt
     * @param allowedPlayers   Namen, die immer dürfen (Groß-/Kleinschreibung egal)
     */
    public record Maintenance(boolean enabled, String bypassPermission, List<String> allowedPlayers) {

        /** Default-Permission für das Team. */
        public static final String DEFAULT_BYPASS_PERMISSION = "bytestorm.maintenance.bypass";

        /**
         * @param enabled          Wartungsmodus aktiv
         * @param bypassPermission Permission, leer nutzt den Default
         * @param allowedPlayers   Ausnahmen, {@code null} wird zu leerer Liste
         */
        public Maintenance {
            bypassPermission = bypassPermission == null || bypassPermission.isBlank()
                    ? DEFAULT_BYPASS_PERMISSION
                    : bypassPermission.trim();
            allowedPlayers = allowedPlayers == null ? List.of() : List.copyOf(allowedPlayers);
        }

        /**
         * @return Wartung aus, Default-Permission, keine Ausnahmen
         */
        public static Maintenance defaults() {
            return new Maintenance(false, DEFAULT_BYPASS_PERMISSION, List.of());
        }
    }

    /**
     * Anbindung an das REST-Modul der Cloud (§11). {@code /cloud} ist damit ein
     * dünner Client: Der Proxy kennt weder Redis noch MongoDB und braucht nur
     * Adresse und API-Key der Cloud.
     *
     * <p>Der Schalter allein reicht nicht — ohne Key bleibt {@code /cloud} aus
     * ({@link #usable()}), damit ein halb ausgefüllter Block keine Anfragen ins
     * Leere schickt.
     *
     * @param enabled        {@code /cloud} anbieten
     * @param baseUrl        Adresse des REST-Moduls, z. B. {@code http://127.0.0.1:8080}
     * @param apiKey         Key aus der Modul-Konfiguration des REST-Moduls
     * @param permission     Admin-Override: darf jedes Kommando, auch ohne {@code bytestorm.command.*}
     * @param timeoutSeconds Zeitlimit einer Anfrage
     */
    public record Cloud(boolean enabled, String baseUrl, String apiKey, String permission, int timeoutSeconds) {

        /** Default-Adresse: Das REST-Modul läuft in der Cloud auf Port 8080. */
        public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8080";

        /** Default-Permission für das Team. */
        public static final String DEFAULT_PERMISSION = "bytestorm.cloud.command";

        /** Default-Zeitlimit; ein Start kann einen Provisioner beschäftigen. */
        public static final int DEFAULT_TIMEOUT_SECONDS = 10;

        /** Obergrenze, damit ein hängender Aufruf den Proxy-Thread nicht blockiert. */
        public static final int MAX_TIMEOUT_SECONDS = 120;

        /**
         * @param enabled        {@code /cloud} anbieten
         * @param baseUrl        Adresse mit {@code http://} oder {@code https://}
         * @param apiKey         Key des REST-Moduls, leer heißt „nicht konfiguriert“
         * @param permission     Admin-Override, leer nutzt den Default
         * @param timeoutSeconds Zeitlimit zwischen 1 und {@value #MAX_TIMEOUT_SECONDS}
         */
        public Cloud {
            baseUrl = baseUrl == null || baseUrl.isBlank()
                    ? DEFAULT_BASE_URL
                    : stripTrailingSlash(baseUrl.trim());
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                throw new IllegalArgumentException(
                        "cloud.baseUrl muss mit http:// oder https:// beginnen: " + baseUrl);
            }
            apiKey = apiKey == null ? "" : apiKey.trim();
            permission = permission == null || permission.isBlank() ? DEFAULT_PERMISSION : permission.trim();
            if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
                throw new IllegalArgumentException("cloud.timeoutSeconds muss zwischen 1 und "
                        + MAX_TIMEOUT_SECONDS + " liegen: " + timeoutSeconds);
            }
        }

        /**
         * @return {@code /cloud} aus, leere Anbindung
         */
        public static Cloud defaults() {
            return new Cloud(false, DEFAULT_BASE_URL, "", DEFAULT_PERMISSION, DEFAULT_TIMEOUT_SECONDS);
        }

        /**
         * @return {@code true}, wenn der Schalter an ist und ein Key vorliegt
         */
        public boolean usable() {
            return enabled && !apiKey.isEmpty();
        }

        private static String stripTrailingSlash(String url) {
            String result = url;
            while (result.endsWith("/")) {
                result = result.substring(0, result.length() - 1);
            }
            return result;
        }
    }
}
