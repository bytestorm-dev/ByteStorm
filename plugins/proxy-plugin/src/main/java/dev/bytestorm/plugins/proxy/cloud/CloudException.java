package dev.bytestorm.plugins.proxy.cloud;

/**
 * Fehler aus Sicht der Cloud: nicht erreichbar, Zeitüberschreitung oder eine
 * fachliche Ablehnung des REST-Moduls.
 *
 * <p>Die Meldung ist für Spieler gedacht; {@code /cloud} zeigt sie im Text
 * {@code cloud.error} an und nennt damit den Grund, statt „Fehler“ zu sagen.
 */
public final class CloudException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message Grund für den Spieler
     */
    public CloudException(String message) {
        super(message);
    }

    /**
     * @param message Grund für den Spieler
     * @param cause   Ursache, etwa eine abgebrochene Verbindung
     */
    public CloudException(String message, Throwable cause) {
        super(message, cause);
    }
}
