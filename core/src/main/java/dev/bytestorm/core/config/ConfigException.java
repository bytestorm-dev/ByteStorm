package dev.bytestorm.core.config;

/**
 * Fehler beim Laden, Validieren oder Neuladen der Cloud-Konfiguration.
 */
public class ConfigException extends Exception {

    /**
     * @param message Beschreibung inklusive Feldpfad
     */
    public ConfigException(String message) {
        super(message);
    }

    /**
     * @param message Beschreibung inklusive Feldpfad
     * @param cause   Ursache, z. B. ein JSON-Parser-Fehler
     */
    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
