package dev.bytestorm.core.redis;

/**
 * Redis ist nicht erreichbar oder die Authentifizierung ist fehlgeschlagen.
 * Die Meldung enthält nie das Passwort (§20).
 */
public class RedisUnavailableException extends RuntimeException {

    /**
     * @param message Beschreibung
     * @param cause   Ursache aus Redisson
     */
    public RedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
