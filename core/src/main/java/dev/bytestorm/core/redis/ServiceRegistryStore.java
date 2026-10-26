package dev.bytestorm.core.redis;

import dev.bytestorm.proto.ServiceInfo;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Live-State der Services unter {@code bs:services} (§7).
 *
 * <p><b>Entscheidung:</b> Die Konzept-Tabelle nennt eine {@code RMap}, verlangt
 * aber gleichzeitig eine TTL je Service-Eintrag. {@code RMap} kennt keine
 * Per-Entry-TTL, daher wird {@link RMapCache} verwendet; die Semantik
 * (Eintrag läuft nach fehlendem Heartbeat ab) bleibt identisch.
 */
public final class ServiceRegistryStore {

    private final RMapCache<String, ServiceInfo> services;

    /**
     * @param client verbundener Redisson-Client
     */
    public ServiceRegistryStore(RedissonClient client) {
        this.services = client.getMapCache(RedisKeys.SERVICES);
    }

    /**
     * Legt einen Service ab bzw. erneuert seine TTL.
     *
     * @param info Service-Information
     * @param ttl  Gültigkeit ohne weiteren Heartbeat
     */
    public void put(ServiceInfo info, Duration ttl) {
        Objects.requireNonNull(info, "info");
        services.put(info.getId(), info, positiveMillis(ttl), TimeUnit.MILLISECONDS);
    }

    /**
     * Erneuert die TTL eines bestehenden Eintrags.
     *
     * @param info aktueller Zustand
     * @param ttl  neue Gültigkeit
     */
    public void heartbeat(ServiceInfo info, Duration ttl) {
        put(info, ttl);
    }

    /**
     * @param serviceId Service-UUID als String
     * @return der Service, falls vorhanden und nicht abgelaufen
     */
    public Optional<ServiceInfo> get(String serviceId) {
        return Optional.ofNullable(services.get(serviceId));
    }

    /**
     * @return alle nicht abgelaufenen Services
     */
    public Collection<ServiceInfo> all() {
        return List.copyOf(services.readAllValues());
    }

    /**
     * @param group Gruppenname
     * @return alle Services dieser Gruppe
     */
    public List<ServiceInfo> byGroup(String group) {
        List<ServiceInfo> result = new ArrayList<>();
        for (ServiceInfo info : services.readAllValues()) {
            if (info.getGroup().equals(group)) {
                result.add(info);
            }
        }
        return result;
    }

    /**
     * @param serviceId Service-UUID als String
     * @return {@code true}, wenn ein Eintrag entfernt wurde
     */
    public boolean remove(String serviceId) {
        return services.remove(serviceId) != null;
    }

    private static long positiveMillis(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl muss positiv sein: " + ttl);
        }
        return ttl.toMillis();
    }
}
