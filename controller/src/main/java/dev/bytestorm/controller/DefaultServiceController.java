package dev.bytestorm.controller;

import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.controller.registry.ServiceRegistry;
import dev.bytestorm.node.lifecycle.ServiceDefinition;
import dev.bytestorm.node.lifecycle.ServiceLifecycleException;
import dev.bytestorm.node.lifecycle.ServiceManager;
import dev.bytestorm.proto.ServiceInfo;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Controller-Fassade für CLI/REST.
 *
 * <p>Lesen läuft über die {@link ServiceRegistry}, deren Quelle der Wahrheit
 * Redis ist; geschrieben wird ausschließlich über den {@link ServiceManager} des
 * Nodes. Damit sieht die CLI auch Services, die nicht auf diesem Node laufen,
 * und der Node bleibt der einzige Pfad, der Zustand verändert (§7.1).
 */
public final class DefaultServiceController implements ServiceController {

    private final ServiceManager manager;
    private final ServiceRegistry registry;

    /**
     * @param manager  Service-Manager des Nodes
     * @param registry Registry über Redis und Node
     */
    public DefaultServiceController(ServiceManager manager, ServiceRegistry registry) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public ServiceInfo start(ServiceDefinition definition) throws ServiceLifecycleException {
        return manager.start(definition);
    }

    @Override
    public ServiceInfo start(ServiceId id) throws ServiceLifecycleException {
        return manager.start(id);
    }

    @Override
    public ServiceInfo stop(ServiceId id, Duration timeout, boolean force) throws ServiceLifecycleException {
        return manager.stop(id, timeout, force);
    }

    @Override
    public ServiceInfo restart(ServiceId id, Duration timeout) throws ServiceLifecycleException {
        return manager.restart(id, timeout);
    }

    @Override
    public ServiceInfo kill(ServiceId id) throws ServiceLifecycleException {
        return manager.kill(id);
    }

    @Override
    public ServiceInfo reapply(ServiceId id) throws ServiceLifecycleException {
        return manager.reapply(id);
    }

    @Override
    public ServiceInfo online(ServiceId id) {
        return manager.online(id);
    }

    @Override
    public void heartbeat(ServiceId id) {
        manager.heartbeat(id);
    }

    @Override
    public List<ServiceId> checkHeartbeats() {
        return manager.checkHeartbeats();
    }

    @Override
    public List<ServiceInfo> services() {
        return registry.all();
    }

    @Override
    public List<ServiceInfo> byGroup(String group) {
        return registry.byGroup(group);
    }

    @Override
    public Optional<ServiceInfo> byName(String name) {
        return registry.byName(name);
    }

    @Override
    public void sendCommand(ServiceId id, String command) throws ServiceLifecycleException {
        manager.sendCommand(id, command);
    }

    @Override
    public List<String> logs(ServiceId id) {
        return manager.logs(id);
    }

    @Override
    public Set<String> degradedGroups() {
        return manager.degradedGroups();
    }
}
