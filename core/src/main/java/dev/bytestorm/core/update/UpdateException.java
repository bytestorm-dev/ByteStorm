package dev.bytestorm.core.update;

/**
 * Fehler beim Abfragen oder Einspielen eines Updates (§17).
 */
public class UpdateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message Meldung für CLI und Log
     */
    public UpdateException(String message) {
        super(message);
    }

    /**
     * @param message Meldung für CLI und Log
     * @param cause   Ursache
     */
    public UpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
