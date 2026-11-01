package dev.bytestorm.module.discord;

/**
 * Zustellung einer Benachrichtigung, z. B. an einen Discord-Webhook (§16).
 *
 * <p>Die Schnittstelle hält das Modul frei vom Transport: Tests setzen einen
 * Sammler ein, die Cloud schickt über HTTP.
 */
interface DiscordNotifier {

    /**
     * @param text Nachricht, bereits formatiert
     * @return {@code true}, wenn der Empfänger sie angenommen hat
     */
    boolean send(String text);
}
