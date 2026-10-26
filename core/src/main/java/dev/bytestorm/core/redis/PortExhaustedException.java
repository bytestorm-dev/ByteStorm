package dev.bytestorm.core.redis;

/**
 * Im angeforderten Bereich ist kein Port mehr frei (§12.4).
 */
public class PortExhaustedException extends RuntimeException {

    /**
     * @param message Beschreibung inklusive Bereich
     */
    public PortExhaustedException(String message) {
        super(message);
    }
}
