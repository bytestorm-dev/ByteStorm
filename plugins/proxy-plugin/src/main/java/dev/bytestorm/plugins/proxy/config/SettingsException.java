package dev.bytestorm.plugins.proxy.config;

/**
 * {@code config.json} ist nicht lesbar oder enthält unbrauchbare Werte (§16).
 *
 * <p>Der Fehler nennt das Feld, damit ein Tippfehler nicht still auf einen
 * Default zurückfällt. Das Plugin behält beim Neuladen die alten Einstellungen.
 */
public final class SettingsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message Meldung mit Feldnamen
     */
    public SettingsException(String message) {
        super(message);
    }

    /**
     * @param message Meldung mit Feldnamen
     * @param cause   Ursache, z. B. ein JSON-Fehler
     */
    public SettingsException(String message, Throwable cause) {
        super(message, cause);
    }
}
