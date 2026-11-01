package dev.bytestorm.launcher.luckperms;

/**
 * LuckPerms konnte nicht bereitgestellt werden — Netzwerk, HTTP, Metadaten oder
 * Dateisystem. Der Service startet trotzdem; die Rechte-Verwaltung fehlt dann.
 */
public class LuckPermsException extends RuntimeException {

    /**
     * @param message Beschreibung mit Plattform und Quelle
     */
    public LuckPermsException(String message) {
        super(message);
    }

    /**
     * @param message Beschreibung mit Plattform und Quelle
     * @param cause   Ursache
     */
    public LuckPermsException(String message, Throwable cause) {
        super(message, cause);
    }
}
