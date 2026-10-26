package dev.bytestorm.api.event;

/**
 * Handle auf ein Event-Abonnement.
 */
public interface Subscription extends AutoCloseable {

    /**
     * Beendet das Abonnement. Mehrfache Aufrufe sind wirkungslos.
     */
    void unsubscribe();

    /**
     * @return {@code true}, solange das Abonnement aktiv ist
     */
    boolean isActive();

    @Override
    default void close() {
        unsubscribe();
    }
}
