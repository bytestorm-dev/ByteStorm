package dev.bytestorm.module.discord;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Einstellungen des Discord-Moduls (§16, §12.5).
 *
 * <p>Die Meldungen gehen an einen Webhook: Das braucht keinen Bot-Token und
 * keine Gateway-Verbindung, sondern nur eine URL, die der Betreiber in Discord
 * anlegt. Sie kommt aus der Modul-Konfiguration ({@code modules_config}), die der
 * {@code ModuleContext} als schreibgeschützte Map reicht.
 *
 * @param webhook          Ziel der Benachrichtigung, immer {@code https}
 * @param notifyOnRecovery auch melden, wenn eine Gruppe wieder läuft
 */
record DiscordSettings(URI webhook, boolean notifyOnRecovery) {

    /**
     * @param webhook          Webhook-URL
     * @param notifyOnRecovery Entwarnung melden
     */
    DiscordSettings {
        Objects.requireNonNull(webhook, "webhook");
    }

    /**
     * @param settings Einstellungen aus der Modul-Konfiguration
     * @return die geprüften Einstellungen
     * @throws IllegalArgumentException wenn der Webhook fehlt oder unbrauchbar ist
     */
    static DiscordSettings from(Map<String, String> settings) {
        Objects.requireNonNull(settings, "settings");
        String raw = value(settings, "webhookUrl");
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("webhookUrl fehlt — Einstellung 'webhookUrl' des Moduls '"
                    + DiscordModule.NAME + "' setzen");
        }
        return new DiscordSettings(parse(raw), flag(settings, "notifyOnRecovery", true));
    }

    private static URI parse(String raw) {
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("webhookUrl ist keine URL: " + e.getMessage(), e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("webhookUrl muss mit https:// beginnen: " + uri.getScheme());
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("webhookUrl hat keinen Host");
        }
        return uri;
    }

    private static boolean flag(Map<String, String> settings, String key, boolean fallback) {
        String raw = value(settings, key);
        if (raw.isEmpty()) {
            return fallback;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "ja", "on", "1" -> true;
            case "false", "nein", "off", "0" -> false;
            default -> throw new IllegalArgumentException(key + " ist kein Wahrheitswert: " + raw);
        };
    }

    private static String value(Map<String, String> settings, String key) {
        String value = settings.get(key);
        return value == null || value.isBlank() ? "" : value.trim();
    }

    /**
     * Der Webhook enthält einen Token; er darf nie im Log auftauchen.
     *
     * @return Beschreibung ohne URL
     */
    @Override
    public String toString() {
        return "Webhook gesetzt, Entwarnung " + (notifyOnRecovery ? "an" : "aus");
    }
}
