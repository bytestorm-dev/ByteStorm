package dev.bytestorm.core.redis;

import dev.bytestorm.core.config.RedisSettings;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.codec.Codec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

import java.util.Objects;

/**
 * Baut und hält den Redisson-Client (§7).
 *
 * <p>Der Client wird mit dem {@link dev.bytestorm.codec.ProtobufCodec}
 * erzeugt, damit Nachrichten, Map-Keys und einfache Werte denselben Codec nutzen.
 */
public final class RedisProvider implements AutoCloseable {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int COMMAND_TIMEOUT_MS = 3_000;
    private static final int RETRY_ATTEMPTS = 3;

    private final RedissonClient client;

    private RedisProvider(RedissonClient client) {
        this.client = client;
    }

    /**
     * Erzeugt den Client und prüft die Verbindung.
     *
     * @param settings Verbindungsdaten
     * @param codec    Codec für alle Werte
     * @return verbundener Provider
     * @throws RedisUnavailableException wenn Redis nicht erreichbar ist
     */
    public static RedisProvider connect(RedisSettings settings, Codec codec) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(codec, "codec");

        Config config = new Config();
        config.setCodec(codec);

        SingleServerConfig server = config.useSingleServer()
                .setAddress(settings.address())
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setTimeout(COMMAND_TIMEOUT_MS)
                .setRetryAttempts(RETRY_ATTEMPTS)
                .setClientName("bytestorm");
        if (settings.password() != null) {
            // Redisson 4.x: Passwort am Top-Level-Config setzen, nicht am Server-Config.
            config.setPassword(settings.password());
        }

        RedissonClient client;
        try {
            client = Redisson.create(config);
        } catch (RedisConnectionException e) {
            throw new RedisUnavailableException("Redis nicht erreichbar unter " + settings.address(), e);
        }

        RedisProvider provider = new RedisProvider(client);
        try {
            provider.ping();
        } catch (RuntimeException e) {
            client.shutdown();
            throw e;
        }
        return provider;
    }

    /**
     * Prüft die Verbindung.
     *
     * @throws RedisUnavailableException wenn Redis nicht antwortet
     */
    public void ping() {
        try {
            client.getBucket(RedisKeys.HEALTH).isExists();
        } catch (RuntimeException e) {
            throw new RedisUnavailableException("Redis antwortet nicht auf " + RedisKeys.HEALTH, e);
        }
    }

    /**
     * @return der verbundene Client
     */
    public RedissonClient client() {
        return client;
    }

    @Override
    public void close() {
        client.shutdown();
    }
}
