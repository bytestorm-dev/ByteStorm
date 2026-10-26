package dev.bytestorm.bridge.common;

/**
 * Fehler beim Aufbau oder Betrieb der Bridge-Verbindung.
 */
public class BridgeException extends RuntimeException {

    /**
     * @param message Beschreibung inklusive Ursache
     */
    public BridgeException(String message) {
        super(message);
    }

    /**
     * @param message Beschreibung inklusive Ursache
     * @param cause   Ursache, z. B. ein Redis-Verbindungsfehler
     */
    public BridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
