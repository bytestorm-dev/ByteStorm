package dev.bytestorm.core.redis;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Kurzlebige Caches auf {@code bs:cache:<name>} (§7).
 */
@RequiredArgsConstructor
public final class CacheService {

    /** Verbundener Redisson-Client. */
    @NonNull
    private final RedissonClient client;

    /**
     * @param cache Cache-Name
     * @param key   Schlüssel
     * @param value Wert
     * @param ttl   Gültigkeit
     */
    public void put(String cache, String key, String value, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl muss positiv sein: " + ttl);
        }
        cache(cache).put(key, value, ttl.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * @param cache Cache-Name
     * @param key   Schlüssel
     * @return Wert, falls vorhanden und nicht abgelaufen
     */
    public Optional<String> get(String cache, String key) {
        return Optional.ofNullable(cache(cache).get(key));
    }

    /**
     * @param cache Cache-Name
     * @param key   Schlüssel
     * @return {@code true}, wenn ein Eintrag entfernt wurde
     */
    public boolean remove(String cache, String key) {
        return cache(cache).remove(key) != null;
    }

    /**
     * Löscht den gesamten Cache.
     *
     * @param cache Cache-Name
     */
    public void clear(String cache) {
        cache(cache).delete();
    }

    private RMapCache<String, String> cache(String name) {
        return client.getMapCache(RedisKeys.cache(name));
    }
}
