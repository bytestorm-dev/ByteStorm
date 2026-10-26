package dev.bytestorm.controller.registry;

import dev.bytestorm.core.redis.ServiceRegistryStore;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Registry über alle Services (§7, §9.2).
 *
 * <p><b>Quelle der Wahrheit ist Redis</b> ({@code bs:services}): Dort steht ein
 * Service erst, wenn seine Bridge sich per Handshake gemeldet hat, und nur
 * solange der Heartbeat nicht abgelaufen ist. Geschrieben wird dieser Zustand
 * ausschließlich vom Node bzw. dem {@code BridgeRegistrar}; diese Registry liest
 * ihn nur.
 *
 * <p>Der Node führt zusätzlich Services, die Redis noch nicht kennt — etwa in
 * {@code PREPARING}/{@code STARTING} vor dem ersten Handshake oder nach einem
 * Stop, solange der Eintrag aufgeräumt wird. Diese Sicht ergänzt die Registry,
 * damit die CLI keine gerade startenden Services verliert. Steht ein Service in
 * Redis, gewinnt dessen Eintrag.
 *
 * <p>Nach einem Neustart der Cloud kann Redis noch Einträge aus dem vorherigen
 * Lauf enthalten, solange ihre TTL läuft. Das ist beabsichtigt: Die Registry
 * zeigt, was tatsächlich erreichbar war; das Aufräumen solcher Reste erledigt
 * die Reconciliation beim Start (§7.1).
 */
public final class ServiceRegistry {

    private final Supplier<Collection<ServiceInfo>> live;
    private final Supplier<List<ServiceInfo>> lifecycle;

    /**
     * @param live      Live-Zustand aus Redis
     * @param lifecycle Zustand des Nodes, auch für noch nicht gemeldete Services
     */
    public ServiceRegistry(Supplier<Collection<ServiceInfo>> live,
                           Supplier<List<ServiceInfo>> lifecycle) {
        this.live = Objects.requireNonNull(live, "live");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /**
     * @param store     Live-Registry in Redis
     * @param lifecycle Zustand des Nodes
     * @return Registry über beide Sichten
     */
    public static ServiceRegistry of(ServiceRegistryStore store, Supplier<List<ServiceInfo>> lifecycle) {
        Objects.requireNonNull(store, "store");
        return new ServiceRegistry(store::all, lifecycle);
    }

    /**
     * @return alle bekannten Services, alphabetisch nach Name
     */
    public List<ServiceInfo> all() {
        Map<String, ServiceInfo> merged = new LinkedHashMap<>();
        for (ServiceInfo info : require(live.get(), "live")) {
            merged.put(info.getId(), info);
        }
        for (ServiceInfo info : require(lifecycle.get(), "lifecycle")) {
            // Nur ergänzen: steht der Service in Redis, gilt der Live-Zustand.
            merged.putIfAbsent(info.getId(), info);
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(ServiceInfo::getName))
                .toList();
    }

    /**
     * @param group Gruppenname
     * @return Services der Gruppe, alphabetisch nach Name
     */
    public List<ServiceInfo> byGroup(String group) {
        Objects.requireNonNull(group, "group");
        return all().stream().filter(info -> info.getGroup().equals(group)).toList();
    }

    /**
     * @param name Service-Name
     * @return der Service, falls bekannt
     */
    public Optional<ServiceInfo> byName(String name) {
        Objects.requireNonNull(name, "name");
        return all().stream().filter(info -> info.getName().equals(name)).findFirst();
    }

    /**
     * @param serviceId Service-UUID als String
     * @return der Service, falls bekannt
     */
    public Optional<ServiceInfo> byId(String serviceId) {
        Objects.requireNonNull(serviceId, "serviceId");
        return all().stream().filter(info -> info.getId().equals(serviceId)).findFirst();
    }

    /**
     * @return Services im Zustand {@code ONLINE}
     */
    public List<ServiceInfo> online() {
        return all().stream().filter(info -> info.getState() == ServiceState.ONLINE).toList();
    }

    /**
     * @return Summe der gemeldeten Spieler über alle {@code ONLINE}-Services
     */
    public long onlinePlayers() {
        return online().stream().mapToLong(ServiceInfo::getOnlinePlayers).sum();
    }

    /**
     * @param name Service-Name
     * @return {@code true}, wenn ein Service mit diesem Namen bekannt ist
     */
    public boolean contains(String name) {
        return byName(name).isPresent();
    }

    private static Collection<ServiceInfo> require(Collection<ServiceInfo> services, String field) {
        return Objects.requireNonNull(services, field);
    }
}
