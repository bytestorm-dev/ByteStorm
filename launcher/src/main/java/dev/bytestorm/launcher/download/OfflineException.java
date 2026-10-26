package dev.bytestorm.launcher.download;

/**
 * Eine Abhängigkeit fehlt und konnte wegen fehlender Netzwerkverbindung nicht
 * geladen werden.
 *
 * <p>Die Meldung nennt Zielpfad, Prüfsumme und Quelle, damit die Datei manuell
 * abgelegt werden kann.
 */
public class OfflineException extends DownloadException {

    /**
     * @param message Anleitung inklusive Zielpfad und Prüfsumme
     * @param cause   Netzwerkfehler, z. B. {@link java.net.ConnectException}
     */
    public OfflineException(String message, Throwable cause) {
        super(message, cause);
    }
}
