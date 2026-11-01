package dev.bytestorm.module.discord;

import dev.bytestorm.proto.GroupHealthChanged;
import dev.bytestorm.proto.GroupState;

/**
 * Übersetzt einen Gesundheitswechsel in eine Chat-Nachricht (§12.5).
 *
 * <p>Die Texte sind Alltagssprache, weil sie im Chat landen: Die DEGRADED-Meldung
 * nennt die Ursache und sagt, was zu tun ist. Alles andere — Wartungsmodus,
 * unbekannte Zustände — erzeugt keine Nachricht.
 */
final class DiscordMessages {

    private DiscordMessages() {
    }

    /**
     * @param event    Gesundheitswechsel aus dem Event-Bus
     * @param settings Einstellungen des Moduls
     * @return Nachricht oder {@code null}, wenn nichts zu melden ist
     */
    static String text(GroupHealthChanged event, DiscordSettings settings) {
        String group = event.getGroup().isBlank() ? "?" : event.getGroup();
        if (event.getState() == GroupState.DEGRADED) {
            String reason = event.getReason().isBlank() ? "unbekannte Ursache" : event.getReason();
            String service = event.getService().isBlank() ? "" : " (" + event.getService() + ")";
            return "\uD83D\uDD3B Gruppe **" + group + "** ist DEGRADED: " + reason + service
                    + ". Automatische Neustarts gestoppt — bitte manuell starten.";
        }
        if (event.getState() == GroupState.HEALTHY) {
            return settings.notifyOnRecovery() ? "\u2705 Gruppe **" + group + "** läuft wieder." : null;
        }
        return null;
    }
}
