package dev.bytestorm.core.redis;

import dev.bytestorm.api.bridge.BridgeChannels;
import lombok.experimental.UtilityClass;

import java.util.Objects;

/**
 * Alle Redis-Schlüssel an einer Stelle (§7).
 */
@UtilityClass
public final class RedisKeys {

    /** Live-State der Services (RMapCache, Einträge mit Heartbeat-TTL). */
    public static final String SERVICES = BridgeChannels.SERVICES;

    /** Live-State der Spieler (RMap). */
    public static final String PLAYERS = "bs:players";

    /** Vergebene Ports (RSet&lt;Integer&gt;). */
    public static final String PORTS = "bs:ports";

    /** RPC-Kanal (RRemoteService). */
    public static final String RPC = "bs:rpc";

    /** Schlüssel für den Verbindungstest. */
    public static final String HEALTH = "bs:health";

    private static final String EVENT_PREFIX = "bs:event:";
    private static final String LOG_PREFIX = "bs:log:";
    private static final String COUNTER_PREFIX = "bs:counter:";
    private static final String CACHE_PREFIX = "bs:cache:";

    /**
     * @param eventType vollqualifizierter Proto-Name, z. B. {@code bytestorm.ServiceStateChanged}
     * @return Topic-Schlüssel {@code bs:event:<type>}
     */
    public static String event(String eventType) {
        return EVENT_PREFIX + requireName(eventType, "eventType");
    }

    /**
     * @param service Service-Name
     * @return Topic-Schlüssel {@code bs:log:<service>}
     */
    public static String log(String service) {
        return LOG_PREFIX + requireName(service, "service");
    }

    /**
     * @param group Gruppenname
     * @return Zähler-Schlüssel {@code bs:counter:<group>}
     */
    public static String counter(String group) {
        return COUNTER_PREFIX + requireName(group, "group");
    }

    /**
     * @param name Cache-Name
     * @return Cache-Schlüssel {@code bs:cache:<name>}
     */
    public static String cache(String name) {
        return CACHE_PREFIX + requireName(name, "name");
    }

    /**
     * @param key Key, der nicht leer sein darf
     * @param field Feldname für die Fehlermeldung
     * @return der unveränderte Key
     */
    private static String requireName(String key, String field) {
        Objects.requireNonNull(key, field);
        if (key.isBlank()) {
            throw new IllegalArgumentException(field + " darf nicht leer sein");
        }
        return key;
    }
}
