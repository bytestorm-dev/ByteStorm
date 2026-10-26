package dev.bytestorm.api.event;

import com.google.protobuf.Message;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Event-Abonnements. Die Verteilung läuft über Redisson-Topics
 * {@code bs:event:<type>} (Konzept §7, §15).
 *
 * <pre>{@code
 * ByteStorm.api().events().subscribe(ServiceStateChanged.class, event ->
 *         System.out.println(event.getService().getName() + " -> " + event.getService().getState()));
 * }</pre>
 */
public interface Events {

    /**
     * Abonniert alle Events eines Typs.
     *
     * @param type     Proto-Nachrichtentyp
     * @param listener Empfänger
     * @param <T>      Nachrichtentyp
     * @return Handle zum Beenden des Abonnements
     */
    default <T extends Message> Subscription subscribe(Class<T> type, Consumer<T> listener) {
        return subscribe(type, event -> true, listener);
    }

    /**
     * Abonniert Events eines Typs, die den Filter erfüllen.
     *
     * @param type     Proto-Nachrichtentyp
     * @param filter   Prädikat, wird vor dem Listener ausgewertet
     * @param listener Empfänger
     * @param <T>      Nachrichtentyp
     * @return Handle zum Beenden des Abonnements
     */
    <T extends Message> Subscription subscribe(Class<T> type, Predicate<T> filter, Consumer<T> listener);
}
