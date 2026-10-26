package dev.bytestorm.node.lifecycle;

import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.node.crash.CrashSupport;
import dev.bytestorm.node.crash.ServiceCrashSupervisor;
import dev.bytestorm.node.runtime.ServiceRuntime;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceStateChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Verwaltet alle Services eines Nodes (§5, §9).
 *
 * <p>Registriert je Service einen {@link ServiceCrashSupervisor}, startet und
 * stoppt über ID oder Name und sammelt degradierte Gruppen. Die Registry ist
 * synchronisiert; die eigentlichen Start-/Stop-Operationen laufen auf dem
 * jeweiligen Service und blockieren nur diesen.
 */
public final class ServiceManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ServiceManager.class);

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    /** Default-Frist ohne Heartbeat, bevor ein Service als unerreichbar gilt. */
    public static final Duration DEFAULT_HEARTBEAT_TIMEOUT = Duration.ofSeconds(30);

    private final ServiceRuntimeProvider runtimes;
    private final ServicePortProvider ports;
    private final ServicePreparer preparer;
    private final Consumer<ServiceStateChanged> stateEvents;
    private final CrashSupport crashes;
    private final Duration heartbeatTimeout;
    private final Clock clock;

    private final Map<ServiceId, ServiceCrashSupervisor> services = new LinkedHashMap<>();
    private final Map<String, ServiceId> names = new LinkedHashMap<>();
    private final Map<ServiceId, Instant> heartbeats = new HashMap<>();
    private final Set<String> degradedGroups = ConcurrentHashMap.newKeySet();

    /**
     * @param runtimes    Runtime je Gruppe
     * @param ports       Port-Pool je Gruppe
     * @param preparer    Ordner-/Template-Vorbereitung
     * @param stateEvents Publisher für Zustandswechsel
     * @param crashes     gemeinsame Crash-Konfiguration
     */
    public ServiceManager(ServiceRuntimeProvider runtimes,
                          ServicePortProvider ports,
                          ServicePreparer preparer,
                          Consumer<ServiceStateChanged> stateEvents,
                          CrashSupport crashes) {
        this(runtimes, ports, preparer, stateEvents, crashes, DEFAULT_HEARTBEAT_TIMEOUT, Clock.systemUTC());
    }

    /**
     * @param runtimes         Runtime je Gruppe
     * @param ports            Port-Pool je Gruppe
     * @param preparer         Ordner-/Template-Vorbereitung
     * @param stateEvents      Publisher für Zustandswechsel
     * @param crashes          gemeinsame Crash-Konfiguration
     * @param heartbeatTimeout Frist ohne Heartbeat
     */
    public ServiceManager(ServiceRuntimeProvider runtimes,
                          ServicePortProvider ports,
                          ServicePreparer preparer,
                          Consumer<ServiceStateChanged> stateEvents,
                          CrashSupport crashes,
                          Duration heartbeatTimeout) {
        this(runtimes, ports, preparer, stateEvents, crashes, heartbeatTimeout, Clock.systemUTC());
    }

    ServiceManager(ServiceRuntimeProvider runtimes,
                   ServicePortProvider ports,
                   ServicePreparer preparer,
                   Consumer<ServiceStateChanged> stateEvents,
                   CrashSupport crashes,
                   Duration heartbeatTimeout,
                   Clock clock) {
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.ports = Objects.requireNonNull(ports, "ports");
        this.preparer = Objects.requireNonNull(preparer, "preparer");
        this.stateEvents = Objects.requireNonNull(stateEvents, "stateEvents");
        this.crashes = Objects.requireNonNull(crashes, "crashes");
        this.heartbeatTimeout = Objects.requireNonNull(heartbeatTimeout, "heartbeatTimeout");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (heartbeatTimeout.isZero() || heartbeatTimeout.isNegative()) {
            throw new IllegalArgumentException("heartbeatTimeout muss positiv sein: " + heartbeatTimeout);
        }
    }

    /**
     * Registriert und startet einen neuen Service.
     *
     * @param definition Service-Definition
     * @return der Service im Zustand {@code STARTING}
     * @throws ServiceLifecycleException wenn der Start fehlschlägt
     * @throws IllegalStateException     wenn ID oder Name bereits vergeben sind
     */
    public synchronized ServiceInfo start(ServiceDefinition definition) throws ServiceLifecycleException {
        Objects.requireNonNull(definition, "definition");
        if (services.containsKey(definition.id()) || names.containsKey(definition.name())) {
            throw new IllegalStateException("Service '" + definition.name() + "' ist bereits registriert");
        }

        ServiceRuntime runtime = Objects.requireNonNull(runtimes.runtimeFor(definition.group()),
                "runtime für " + definition.group().getName());
        ServiceInstance instance = new ServiceInstance(definition, runtime,
                () -> ports.acquire(definition.group(), definition.name()),
                preparer, stateEvents);
        ServiceCrashSupervisor supervisor = new ServiceCrashSupervisor(instance,
                crashes.settings(), crashes.reports(), crashes.events(), crashes.scheduler(), group -> {
            degradedGroups.add(group);
            crashes.degraded().accept(group);
        });

        services.put(definition.id(), supervisor);
        names.put(definition.name(), definition.id());
        try {
            return supervisor.start();
        } catch (ServiceLifecycleException | RuntimeException e) {
            services.remove(definition.id());
            names.remove(definition.name());
            throw e;
        }
    }

    /**
     * @param id Service-ID
     * @return der Service im Zustand {@code STARTING}
     * @throws ServiceLifecycleException wenn der Start fehlschlägt
     */
    public ServiceInfo start(ServiceId id) throws ServiceLifecycleException {
        return require(id).start();
    }

    /**
     * @param name Service-Name
     * @return der Service im Zustand {@code STARTING}
     * @throws ServiceLifecycleException wenn der Start fehlschlägt
     */
    public ServiceInfo start(String name) throws ServiceLifecycleException {
        return start(requireId(name));
    }

    /**
     * @param id      Service-ID
     * @param timeout Graceful-Timeout
     * @param force   {@code true} killt sofort
     * @return der Service im Zustand {@code STOPPED}
     * @throws ServiceLifecycleException wenn der Stop fehlschlägt
     */
    public ServiceInfo stop(ServiceId id, Duration timeout, boolean force) throws ServiceLifecycleException {
        ServiceInfo info = require(id).stop(timeout, force);
        synchronized (this) {
            heartbeats.remove(id);
        }
        return info;
    }

    /**
     * @param name    Service-Name
     * @param timeout Graceful-Timeout
     * @param force   {@code true} killt sofort
     * @return der Service im Zustand {@code STOPPED}
     * @throws ServiceLifecycleException wenn der Stop fehlschlägt
     */
    public ServiceInfo stop(String name, Duration timeout, boolean force) throws ServiceLifecycleException {
        return stop(requireId(name), timeout, force);
    }

    /**
     * Stoppt den Service und startet ihn anschließend neu.
     *
     * @param id      Service-ID
     * @param timeout Graceful-Timeout des Stops
     * @return der Service im Zustand {@code STARTING}
     * @throws ServiceLifecycleException wenn Stop oder Start fehlschlagen
     */
    public ServiceInfo restart(ServiceId id, Duration timeout) throws ServiceLifecycleException {
        stop(id, timeout, false);
        return start(id);
    }

    /**
     * Beendet den Service sofort.
     *
     * @param id Service-ID
     * @return der Service im Zustand {@code STOPPED}
     * @throws ServiceLifecycleException wenn der Kill fehlschlägt
     */
    public ServiceInfo kill(ServiceId id) throws ServiceLifecycleException {
        return stop(id, Duration.ZERO, true);
    }

    /**
     * Wendet die Templates eines gestoppten Services erneut an.
     *
     * @param id Service-ID
     * @return der unveränderte Service-Zustand
     * @throws ServiceLifecycleException wenn das erneute Anwenden fehlschlägt
     */
    public ServiceInfo reapply(ServiceId id) throws ServiceLifecycleException {
        ServiceCrashSupervisor supervisor = require(id);
        supervisor.instance().reapply();
        return supervisor.instance().info();
    }

    /**
     * @param id Service-ID
     * @return der Service im Zustand {@code ONLINE}
     */
    public ServiceInfo online(ServiceId id) {
        ServiceCrashSupervisor supervisor = require(id);
        // Ein erneuter Handshake nach einem Bridge-Reconnect darf nicht scheitern.
        ServiceInfo current = supervisor.instance().info();
        ServiceInfo info = current.getState() == ServiceState.ONLINE
                ? current
                : supervisor.online();
        synchronized (this) {
            heartbeats.put(id, clock.instant());
        }
        return info;
    }

    /**
     * Vermerkt einen Heartbeat der Bridge.
     *
     * @param id Service-ID
     * @throws IllegalArgumentException wenn der Service nicht registriert ist
     */
    public void heartbeat(ServiceId id) {
        require(id);
        synchronized (this) {
            heartbeats.put(id, clock.instant());
        }
    }

    /**
     * @param name Service-Name
     */
    public void heartbeat(String name) {
        heartbeat(requireId(name));
    }

    /**
     * Prüft alle {@code ONLINE}-Services auf einen ausgebliebenen Heartbeat und
     * meldet sie über den Crash-Pfad.
     *
     * @return IDs der als unerreichbar erkannten Services
     */
    public List<ServiceId> checkHeartbeats() {
        Instant now = clock.instant();
        List<Timeout> timeouts = new ArrayList<>();
        synchronized (this) {
            for (Map.Entry<ServiceId, ServiceCrashSupervisor> entry : services.entrySet()) {
                ServiceCrashSupervisor supervisor = entry.getValue();
                if (supervisor.instance().info().getState() != ServiceState.ONLINE) {
                    continue;
                }
                Instant seen = heartbeats.get(entry.getKey());
                if (seen == null || Duration.between(seen, now).compareTo(heartbeatTimeout) <= 0) {
                    continue;
                }
                supervisor.instance().heartbeatTimedOut()
                        .ifPresent(exit -> timeouts.add(new Timeout(entry.getKey(), supervisor, exit)));
            }
        }
        for (Timeout timeout : timeouts) {
            timeout.supervisor().reportExit(timeout.exit());
        }
        return timeouts.stream().map(Timeout::id).toList();
    }

    /**
     * Leitet einen Konsolenbefehl an einen laufenden Service weiter.
     *
     * @param id      Service-ID
     * @param command Befehl ohne Zeilenende
     * @throws ServiceLifecycleException wenn der Service nicht läuft oder keine Konsole hat
     */
    public void sendCommand(ServiceId id, String command) throws ServiceLifecycleException {
        require(id).instance().sendCommand(command);
    }

    /**
     * @param id Service-ID
     * @return letzte Logzeilen
     */
    public List<String> logs(ServiceId id) {
        return require(id).instance().logs();
    }

    /**
     * @return alle registrierten Services
     */
    public synchronized List<ServiceInfo> all() {
        return services.values().stream().map(service -> service.instance().info()).toList();
    }

    /**
     * @param group Gruppenname
     * @return Services dieser Gruppe
     */
    public synchronized List<ServiceInfo> byGroup(String group) {
        return services.values().stream()
                .map(service -> service.instance().info())
                .filter(info -> info.getGroup().equals(group))
                .toList();
    }

    /**
     * @param id Service-ID
     * @return der Service, falls registriert
     */
    public synchronized Optional<ServiceInfo> info(ServiceId id) {
        return Optional.ofNullable(services.get(id)).map(service -> service.instance().info());
    }

    /**
     * @param id Service-ID
     * @return der Supervisor, falls registriert
     */
    public synchronized Optional<ServiceCrashSupervisor> supervisor(ServiceId id) {
        return Optional.ofNullable(services.get(id));
    }

    /**
     * Entfernt einen gestoppten Service aus der Registry.
     *
     * @param id Service-ID
     * @return {@code true}, wenn er entfernt wurde
     * @throws IllegalStateException wenn der Service noch läuft
     */
    public synchronized boolean remove(ServiceId id) {
        ServiceCrashSupervisor supervisor = services.get(id);
        if (supervisor == null) {
            return false;
        }
        if (supervisor.instance().info().getState() != ServiceState.STOPPED) {
            throw new IllegalStateException("Service '" + supervisor.instance().definition().name()
                    + "' ist nicht gestoppt");
        }
        services.remove(id);
        names.remove(supervisor.instance().definition().name());
        heartbeats.remove(id);
        return true;
    }

    /**
     * @return Gruppen, die als degradiert gemeldet wurden
     */
    public Set<String> degradedGroups() {
        return Set.copyOf(degradedGroups);
    }

    /**
     * Stoppt alle laufenden Services.
     *
     * @param timeout Graceful-Timeout je Service
     */
    public void stopAll(Duration timeout) {
        for (ServiceCrashSupervisor supervisor : snapshot()) {
            if (supervisor.instance().info().getState() == ServiceState.STOPPED) {
                continue;
            }
            try {
                supervisor.stop(timeout, false);
            } catch (ServiceLifecycleException | RuntimeException e) {
                log.warn("Service '{}' konnte beim Herunterfahren nicht gestoppt werden: {}",
                        supervisor.instance().definition().name(), e.getMessage());
                try {
                    supervisor.stop(Duration.ZERO, true);
                } catch (ServiceLifecycleException | RuntimeException forced) {
                    log.warn("Erzwungener Stop von '{}' fehlgeschlagen: {}",
                            supervisor.instance().definition().name(), forced.getMessage());
                }
            }
        }
    }

    @Override
    public void close() {
        stopAll(SHUTDOWN_TIMEOUT);
        synchronized (this) {
            services.clear();
            names.clear();
            heartbeats.clear();
        }
    }

    private record Timeout(ServiceId id, ServiceCrashSupervisor supervisor, ServiceExit exit) {
    }

    private synchronized List<ServiceCrashSupervisor> snapshot() {
        return new ArrayList<>(services.values());
    }

    private synchronized ServiceCrashSupervisor require(ServiceId id) {
        Objects.requireNonNull(id, "id");
        ServiceCrashSupervisor supervisor = services.get(id);
        if (supervisor == null) {
            throw new IllegalArgumentException("Unbekannter Service: " + id);
        }
        return supervisor;
    }

    private synchronized ServiceId requireId(String name) {
        Objects.requireNonNull(name, "name");
        ServiceId id = names.get(name);
        if (id == null) {
            throw new IllegalArgumentException("Unbekannter Service: " + name);
        }
        return id;
    }
}
