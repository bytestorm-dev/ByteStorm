package dev.bytestorm.core.mongo;

/**
 * MongoDB ist nicht erreichbar oder die Zugangsdaten sind falsch. Die Meldung
 * enthält die redigierte URI, nie das Passwort (§20).
 */
public class MongoUnavailableException extends RuntimeException {

    /**
     * @param message Beschreibung inklusive redigierter URI
     * @param cause   Ursache des Treibers
     */
    public MongoUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
