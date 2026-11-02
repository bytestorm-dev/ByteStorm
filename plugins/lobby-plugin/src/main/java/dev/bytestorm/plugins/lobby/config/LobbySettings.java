package dev.bytestorm.plugins.lobby.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Einstellungen des Lobby-Plugins (§15, §20).
 *
 * <p>Die Redis-Adresse kommt aus der {@code config.yml} oder — wenn dort nichts
 * steht — aus denselben {@code BYTESTORM_*}-Variablen, die der Node jedem Service
 * mitgibt. Ein Lobby-Server, den die Cloud gestartet hat, braucht damit keine
 * eigene Konfiguration; die Datei ist für manuell gestartete Server da.
 *
 * <p>Sichtbare Texte stehen nicht hier, sondern unter {@code messages/};
 * {@code language} wählt die Datei.
 *
 * @param language             Sprachcode, z. B. {@code de}
 * @param redisAddress         Adresse mit {@code redis://} oder {@code rediss://}
 * @param redisPassword        Passwort oder {@code null}
 * @param refreshSeconds       Abstand, in dem die Service-Liste gelesen wird
 * @param animationRadius      Entfernung, ab der animiert wird
 * @param switchCooldownMillis Sperre zwischen zwei Wechseln je Spieler
 */
public record LobbySettings(
        String language,
        String redisAddress,
        @Nullable String redisPassword,
        int refreshSeconds,
        double animationRadius,
        long switchCooldownMillis) {

    /** Umgebungsvariable der Redis-Adresse, wie sie {@code BridgeSettings} nutzt. */
    public static final String ENV_REDIS_ADDRESS = "BYTESTORM_REDIS_ADDRESS";

    /** Umgebungsvariable des Redis-Passworts, wie sie {@code BridgeSettings} nutzt. */
    public static final String ENV_REDIS_PASSWORD = "BYTESTORM_REDIS_PASSWORD";

    /** Sprache der mitgelieferten Texte, die ohne Angabe gilt. */
    public static final String DEFAULT_LANGUAGE = "de";

    private static final int DEFAULT_REFRESH_SECONDS = 2;
    private static final double DEFAULT_ANIMATION_RADIUS = 64;
    private static final long DEFAULT_SWITCH_COOLDOWN_MILLIS = 2_000L;

    private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z]{2,3}(?:_[A-Z]{2})?");

    /**
     * @param language             Sprachcode
     * @param redisAddress         Adresse
     * @param redisPassword        Passwort
     * @param refreshSeconds       Aktualisierungsabstand
     * @param animationRadius      Animationsradius
     * @param switchCooldownMillis Sperre zwischen zwei Wechseln
     */
    public LobbySettings {
        language = language == null || language.isBlank() ? DEFAULT_LANGUAGE : language.trim();
        if (!LANGUAGE_CODE.matcher(language).matches()) {
            throw new IllegalArgumentException("language ist kein Sprachcode (z. B. 'de' oder 'pt_BR'): "
                    + language);
        }
    }

    /**
     * Liest die Einstellungen. Fehlt die Adresse überall, wirft der Aufruf —
     * ein Plugin ohne Redis kann keine Anzeige befüllen und soll das beim Start
     * sagen, nicht bei jedem Klick.
     *
     * @param config      geladene {@code config.yml}
     * @param environment Umgebungsvariablen des Prozesses
     * @return geprüfte Einstellungen
     * @throws IllegalArgumentException wenn die Redis-Adresse fehlt oder ein Wert unbrauchbar ist
     */
    public static LobbySettings from(FileConfiguration config, Map<String, String> environment) {
        String address = firstNonBlank(config.getString("redis.address"),
                environment.get(ENV_REDIS_ADDRESS));
        if (address == null) {
            throw new IllegalArgumentException("redis.address fehlt und "
                    + ENV_REDIS_ADDRESS + " ist nicht gesetzt");
        }

        String password = firstNonBlank(config.getString("redis.password"),
                environment.get(ENV_REDIS_PASSWORD));

        return new LobbySettings(
                config.getString("language", DEFAULT_LANGUAGE),
                normalizeAddress(address),
                password,
                clamp(config.getInt("display.refreshSeconds", DEFAULT_REFRESH_SECONDS), 1, 60),
                clamp(config.getDouble("display.animationRadius", DEFAULT_ANIMATION_RADIUS), 8, 128),
                clamp(config.getLong("display.switchCooldownMillis", DEFAULT_SWITCH_COOLDOWN_MILLIS),
                        0, 10_000));
    }

    /**
     * @param address Adresse mit oder ohne Schema
     * @return Adresse, wie Redisson sie erwartet
     */
    static String normalizeAddress(String address) {
        String trimmed = address.trim();
        return trimmed.startsWith("redis://") || trimmed.startsWith("rediss://")
                ? trimmed
                : "redis://" + trimmed;
    }

    private static @Nullable String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.min(max, Math.max(min, value));
    }

    @Override
    public String toString() {
        return "LobbySettings[language=" + language
                + ", redisAddress=" + redisAddress
                + ", redisPassword=" + (redisPassword == null ? "nicht gesetzt" : "***")
                + ", refreshSeconds=" + refreshSeconds
                + ", animationRadius=" + animationRadius
                + ", switchCooldownMillis=" + switchCooldownMillis + "]";
    }
}
