package dev.bytestorm.core.redis;

import dev.bytestorm.api.event.Subscription;
import dev.bytestorm.proto.LogLine;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Log-Streaming über {@code bs:log:<service>} (§7, §12.2).
 */
@RequiredArgsConstructor
public final class LogTopic {

    /** Verbundener Redisson-Client. */
    @NonNull
    private final RedissonClient client;

    /**
     * @param line Logzeile mit Service-Namen
     * @return Anzahl der Empfänger
     */
    public long publish(LogLine line) {
        return client.getTopic(RedisKeys.log(line.getService())).publish(line);
    }

    /**
     * Abonniert die Logs eines Services.
     *
     * @param service  Service-Name
     * @param listener Empfänger
     * @return Handle zum Beenden des Abonnements
     */
    public Subscription subscribe(String service, Consumer<LogLine> listener) {
        Objects.requireNonNull(listener, "listener");
        RTopic topic = client.getTopic(RedisKeys.log(service));
        int listenerId = topic.addListener(LogLine.class, (channel, message) -> listener.accept(message));

        AtomicBoolean active = new AtomicBoolean(true);
        return new Subscription() {
            @Override
            public void unsubscribe() {
                if (active.compareAndSet(true, false)) {
                    topic.removeListener(listenerId);
                }
            }

            @Override
            public boolean isActive() {
                return active.get();
            }
        };
    }
}
