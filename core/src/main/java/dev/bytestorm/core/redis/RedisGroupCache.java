package dev.bytestorm.core.redis;

import dev.bytestorm.proto.ServiceGroup;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Gruppen-Cache auf {@code bs:cache:groups} (§7).
 *
 * <p>Wie {@link ServiceRegistryStore} nutzt der Cache eine {@code RMapCache} mit
 * TTL je Eintrag und den Codec der Verbindung, sodass die Proto-Nachricht ohne
 * zusätzliche Serialisierung abgelegt werden kann.
 */
public final class RedisGroupCache implements GroupCache {

    /** Wie lange eine Gruppe ohne Änderung aus dem Cache bedient wird. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final RMapCache<String, ServiceGroup> groups;
    private final Duration ttl;

    /**
     * @param client verbundener Redisson-Client
     */
    public RedisGroupCache(RedissonClient client) {
        this(client, DEFAULT_TTL);
    }

    /**
     * @param client verbundener Redisson-Client
     * @param ttl    Gültigkeit eines Eintrags
     */
    public RedisGroupCache(RedissonClient client, Duration ttl) {
        Objects.requireNonNull(client, "client");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl muss positiv sein: " + ttl);
        }
        this.groups = client.getMapCache(RedisKeys.cache("groups"));
    }

    @Override
    public Optional<ServiceGroup> get(String name) {
        return Optional.ofNullable(groups.get(requireName(name)));
    }

    @Override
    public void put(ServiceGroup group) {
        Objects.requireNonNull(group, "group");
        groups.put(group.getName(), group, ttl.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean invalidate(String name) {
        return groups.remove(requireName(name)) != null;
    }

    @Override
    public void clear() {
        groups.clear();
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name darf nicht leer sein");
        }
        return name;
    }
}
