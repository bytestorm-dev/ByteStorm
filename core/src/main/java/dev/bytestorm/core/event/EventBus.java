package dev.bytestorm.core.event;

import com.google.protobuf.Message;
import dev.bytestorm.api.event.Subscription;
import dev.bytestorm.codec.TypeRegistry;
import dev.bytestorm.core.redis.RedisKeys;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Event-Bus über Redisson-Topics (§7, §9).
 *
 * <p>Pro Nachrichtentyp existiert genau ein Topic {@code bs:event:<Proto-Name>}.
 * Alle lokalen Abonnenten eines Typs teilen sich einen Redisson-Listener; erst der
 * letzte Abonnent entfernt ihn wieder.
 *
 * <p>Die Verteilung läuft ausschließlich über Redis. Damit erreichen Events auch
 * den Publisher selbst, und es gibt keine doppelte Zustellung durch einen
 * zusätzlichen lokalen Pfad. Fehler in Filtern oder Listenern werden geloggt und
 * stoppen weder den Bus noch andere Abonnenten.
 */
@Slf4j
@RequiredArgsConstructor
public final class EventBus implements AutoCloseable {

    /** Verbundener Redisson-Client. */
    @NonNull
    private final RedissonClient client;

    /** Registry für die Auflösung Klasse → Topic-Name. */
    @NonNull
    private final TypeRegistry registry;

    private final Map<String, Channel> channels = new HashMap<>();

    /**
     * Abonniert alle Events eines Typs.
     *
     * @param type     Proto-Nachrichtentyp
     * @param listener Empfänger
     * @param <T>      Nachrichtentyp
     * @return Handle zum Beenden des Abonnements
     */
    public <T extends Message> Subscription subscribe(Class<T> type, Consumer<T> listener) {
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
    public synchronized <T extends Message> Subscription subscribe(Class<T> type,
                                                                  Predicate<T> filter,
                                                                  Consumer<T> listener) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(listener, "listener");

        String eventName = registry.fullNameFor(type);
        Channel channel = channels.computeIfAbsent(eventName,
                name -> new Channel(name, client.getTopic(RedisKeys.event(name))));
        LocalSubscription<T> subscription = new LocalSubscription<>(channel, type, filter, listener);
        channel.add(subscription);
        return subscription;
    }

    /**
     * Veröffentlicht ein Event an alle Abonnenten (auch lokal).
     *
     * @param event Proto-Nachricht
     * @param <T>   Nachrichtentyp
     * @return Anzahl der Empfänger in Redis
     */
    public <T extends Message> long publish(T event) {
        Objects.requireNonNull(event, "event");
        String eventName = registry.fullNameFor(event.getClass());
        return client.getTopic(RedisKeys.event(eventName)).publish(event);
    }

    /**
     * @return Anzahl der lokalen Abonnements
     */
    public synchronized int subscriptionCount() {
        return channels.values().stream().mapToInt(channel -> channel.subscriptions.size()).sum();
    }

    /**
     * Entfernt alle lokalen Listener und Abonnements.
     */
    @Override
    public synchronized void close() {
        for (Channel channel : new ArrayList<>(channels.values())) {
            channel.close();
        }
        channels.clear();
    }

    /**
     * Ein Topic je Nachrichtentyp mit genau einem Redisson-Listener.
     */
    private final class Channel {

        private final String name;
        private final RTopic topic;
        private final Set<LocalSubscription<?>> subscriptions = new HashSet<>();
        private int listenerId = -1;

        private Channel(String name, RTopic topic) {
            this.name = name;
            this.topic = topic;
        }

        private synchronized void add(LocalSubscription<?> subscription) {
            subscriptions.add(subscription);
            if (listenerId < 0) {
                listenerId = topic.addListener(Message.class, (channel, message) -> dispatch(message));
            }
        }

        /**
         * @return {@code true}, wenn danach kein Abonnement mehr existiert
         */
        private synchronized boolean remove(LocalSubscription<?> subscription) {
            subscriptions.remove(subscription);
            if (!subscriptions.isEmpty()) {
                return false;
            }
            if (listenerId >= 0) {
                topic.removeListener(listenerId);
                listenerId = -1;
            }
            return true;
        }

        private void dispatch(Message message) {
            List<LocalSubscription<?>> snapshot;
            synchronized (this) {
                snapshot = List.copyOf(subscriptions);
            }
            for (LocalSubscription<?> subscription : snapshot) {
                subscription.deliver(message);
            }
        }

        private synchronized void close() {
            for (LocalSubscription<?> subscription : subscriptions) {
                subscription.deactivate();
            }
            subscriptions.clear();
            if (listenerId >= 0) {
                topic.removeListener(listenerId);
                listenerId = -1;
            }
        }
    }

    /**
     * Lokales Abonnement. Filter- und Listener-Fehler bleiben hier.
     */
    private final class LocalSubscription<T extends Message> implements Subscription {

        private final Channel channel;
        private final Class<T> type;
        private final Predicate<T> filter;
        private final Consumer<T> listener;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private LocalSubscription(Channel channel, Class<T> type, Predicate<T> filter, Consumer<T> listener) {
            this.channel = channel;
            this.type = type;
            this.filter = filter;
            this.listener = listener;
        }

        @Override
        public void unsubscribe() {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            synchronized (EventBus.this) {
                if (channel.remove(this)) {
                    channels.remove(channel.name);
                }
            }
        }

        @Override
        public boolean isActive() {
            return active.get();
        }

        private void deactivate() {
            active.set(false);
        }

        private void deliver(Message message) {
            if (!active.get()) {
                return;
            }
            T value;
            try {
                value = type.cast(message);
            } catch (ClassCastException e) {
                log.warn("Event {} passt nicht zu Abonnement {}", message.getClass().getName(), type.getName());
                return;
            }
            try {
                if (filter.test(value)) {
                    listener.accept(value);
                }
            } catch (RuntimeException e) {
                log.error("Event-Listener für {} ist fehlgeschlagen", type.getSimpleName(), e);
            }
        }
    }
}
