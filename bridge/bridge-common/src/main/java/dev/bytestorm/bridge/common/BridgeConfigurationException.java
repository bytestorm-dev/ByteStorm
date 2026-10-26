package dev.bytestorm.bridge.common;

/**
 * Ungültige oder fehlende Bridge-Konfiguration.
 */
public class BridgeConfigurationException extends BridgeException {

    /**
     * @param message Beschreibung inklusive Feld
     */
    public BridgeConfigurationException(String message) {
        super(message);
    }
}
