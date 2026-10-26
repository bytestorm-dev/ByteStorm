package dev.bytestorm.node.runtime;

import dev.bytestorm.core.config.RedisSettings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Umgebungsvariablen, über die die Bridge ihren Service und Redis findet
 * (Konzept §12.2, §20).
 *
 * <p>Die Werte stehen nur in der Umgebung des Kindprozesses, nie in Argumenten,
 * Templates oder Logs. {@code BYTESTORM_REDIS_PASSWORD} ist ein Secret und darf
 * nicht protokolliert werden.
 */
public final class BridgeEnvironment {

    /** Service-UUID. */
    public static final String SERVICE_ID = "BYTESTORM_SERVICE_ID";

    /** Service-Name, z. B. {@code Lobby-1}. */
    public static final String SERVICE_NAME = "BYTESTORM_SERVICE_NAME";

    /** Redis-Adresse, z. B. {@code redis://localhost:6379}. */
    public static final String REDIS_ADDRESS = "BYTESTORM_REDIS_ADDRESS";

    /** Redis-Passwort. */
    public static final String REDIS_PASSWORD = "BYTESTORM_REDIS_PASSWORD";

    private BridgeEnvironment() {
    }

    /**
     * @param launch Service mit Redis-Verbindung
     * @return die Variablen für den Kindprozess
     */
    public static Map<String, String> of(ServiceLaunch launch) {
        RedisSettings redis = launch.redis();
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(SERVICE_ID, launch.id().toString());
        environment.put(SERVICE_NAME, launch.name());
        environment.put(REDIS_ADDRESS, redis.address());
        if (redis.password() != null) {
            environment.put(REDIS_PASSWORD, redis.password());
        }
        return environment;
    }
}
