package dev.bytestorm.launcher.download;

/**
 * Fehler beim Laden einer Abhängigkeit: Netzwerkfehler, HTTP-Fehler,
 * Prüfsummen-Abweichung oder Abbruch.
 */
public class DownloadException extends RuntimeException {

    /**
     * @param message Beschreibung inklusive Artefakt und Ursache
     */
    public DownloadException(String message) {
        super(message);
    }

    /**
     * @param message Beschreibung inklusive Artefakt und Ursache
     * @param cause   Ursache, z. B. ein I/O-Fehler
     */
    public DownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
