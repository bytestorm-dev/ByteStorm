package dev.bytestorm.launcher.platform;

/**
 * Fehler beim Auflösen oder Laden einer Plattform-Jar: unbekanntes Projekt,
 * keine stabile Version, HTTP-Fehler, Prüfsummen-Abweichung oder Abbruch.
 */
public class PlatformDownloadException extends RuntimeException {

    /**
     * @param message Beschreibung inklusive Projekt und Version
     */
    public PlatformDownloadException(String message) {
        super(message);
    }

    /**
     * @param message Beschreibung inklusive Projekt und Version
     * @param cause   Ursache, z. B. ein I/O- oder Netzwerkfehler
     */
    public PlatformDownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
