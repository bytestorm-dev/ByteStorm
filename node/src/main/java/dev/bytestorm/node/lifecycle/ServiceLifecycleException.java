package dev.bytestorm.node.lifecycle;

/**
 * Fehler beim Starten oder Vorbereiten eines Services.
 */
public class ServiceLifecycleException extends Exception {

    /**
     * @param message Beschreibung inklusive Service
     */
    public ServiceLifecycleException(String message) {
        super(message);
    }

    /**
     * @param message Beschreibung inklusive Service
     * @param cause   Ursache, z. B. ein IO-Fehler beim Template-Kopieren
     */
    public ServiceLifecycleException(String message, Throwable cause) {
        super(message, cause);
    }
}
