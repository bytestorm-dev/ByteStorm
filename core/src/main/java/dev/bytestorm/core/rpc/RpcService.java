package dev.bytestorm.core.rpc;

import dev.bytestorm.core.redis.RedisKeys;
import org.redisson.api.RRemoteService;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * RPC über Redisson unter {@code bs:rpc} (§7).
 *
 * <p>Server registrieren eine Implementierung mit einer festen Worker-Anzahl,
 * Clients erhalten einen Proxy mit Timeout. Läuft der Timeout ab oder ist kein
 * Worker registriert, wirft Redisson eine {@code RemoteServiceTimeoutException};
 * Argumente und Ergebnisse laufen über den {@code ProtobufCodec}.
 */
public final class RpcService {

    /** Default-Timeout für Client-Aufrufe. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final RRemoteService remote;

    /**
     * @param client verbundener Redisson-Client
     */
    public RpcService(RedissonClient client) {
        this.remote = Objects.requireNonNull(client, "client").getRemoteService(RedisKeys.RPC);
    }

    /**
     * @param type RPC-Interface
     * @param <T>  Interface-Typ
     * @return Proxy mit {@link #DEFAULT_TIMEOUT}
     */
    public <T> T client(Class<T> type) {
        return client(type, DEFAULT_TIMEOUT);
    }

    /**
     * @param type    RPC-Interface
     * @param timeout Timeout je Aufruf
     * @param <T>     Interface-Typ
     * @return Proxy mit dem angegebenen Timeout
     */
    public <T> T client(Class<T> type, Duration timeout) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout muss positiv sein: " + timeout);
        }
        return remote.get(type, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Registriert einen Worker mit einem freien Worker.
     *
     * @param type           RPC-Interface
     * @param implementation Implementierung
     * @param <T>            Interface-Typ
     */
    public <T> void register(Class<T> type, T implementation) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(implementation, "implementation");
        remote.register(type, implementation);
    }

    /**
     * Registriert mehrere Worker, damit parallele Aufrufe nicht serialisiert werden.
     *
     * @param type           RPC-Interface
     * @param implementation Implementierung (muss thread-safe sein)
     * @param workers        Anzahl paralleler Worker
     * @param <T>            Interface-Typ
     */
    public <T> void register(Class<T> type, T implementation, int workers) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(implementation, "implementation");
        if (workers < 1) {
            throw new IllegalArgumentException("workers muss mindestens 1 sein: " + workers);
        }
        remote.register(type, implementation, workers);
    }

    /**
     * Meldet alle Worker eines Interface ab.
     *
     * @param type RPC-Interface
     * @param <T>  Interface-Typ
     */
    public <T> void deregister(Class<T> type) {
        remote.deregister(type);
    }

    /**
     * @param type RPC-Interface
     * @return aktuell freie Worker
     */
    public int freeWorkers(Class<?> type) {
        return remote.getFreeWorkers(type);
    }
}
