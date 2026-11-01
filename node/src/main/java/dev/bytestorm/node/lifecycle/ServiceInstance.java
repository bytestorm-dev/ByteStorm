package dev.bytestorm.node.lifecycle;

import dev.bytestorm.api.service.ServiceHandle;
import dev.bytestorm.node.runtime.CommandAware;
import dev.bytestorm.node.runtime.ExitAware;
import dev.bytestorm.node.runtime.PortLease;
import dev.bytestorm.node.runtime.ServiceLaunch;
import dev.bytestorm.node.runtime.ServiceRuntime;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceStateChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Lifecycle eines einzelnen Services (Konzept §9.2, §12).
 *
 * <p>Alle Übergänge sind pro Service serialisiert ({@code synchronized}), weil
 * Start, Stop und Reapply sonst gleichzeitig denselben Ordner und Port
 * verändern könnten. Jeder Übergang wird als {@link ServiceStateChanged}
 * publiziert. {@code ONLINE} setzt die Bridge, sobald sie {@code ready} meldet;
 * der Lifecycle kann das nicht selbst erkennen.
 *
 * <p>Dynamische Services werden nach dem Stop aufgeräumt; statische behalten
 * ihren Ordner. Templates werden nur über {@link ServicePreparer} angefasst,
 * dessen echte Implementierung in Phase 5 folgt.
 */
public final class ServiceInstance {

    private static final Logger log = LoggerFactory.getLogger(ServiceInstance.class);

    private static final String HOST = "localhost";

    private final ServiceDefinition definition;
    private final ServiceRuntime runtime;
    private final Supplier<PortLease> ports;
    private final ServicePreparer preparer;
    private final Consumer<ServiceStateChanged> events;
    private final List<Consumer<ServiceExit>> exitListeners = new CopyOnWriteArrayList<>();

    private ServiceInfo info;
    private ServiceHandle handle;
    private PortLease port;

    /**
     * @param definition Service-Definition
     * @param runtime    Process- oder Docker-Runtime
     * @param ports      liefert eine reservierte Port-Lease
     * @param preparer   Ordner-/Template-Vorbereitung
     * @param events     Publisher für Zustandswechsel
     */
    public ServiceInstance(ServiceDefinition definition,
                           ServiceRuntime runtime,
                           Supplier<PortLease> ports,
                           ServicePreparer preparer,
                           Consumer<ServiceStateChanged> events) {
        this(definition, runtime, ports, preparer, events, exit -> {
        });
    }

    /**
     * @param definition   Service-Definition
     * @param runtime      Process- oder Docker-Runtime
     * @param ports        liefert eine reservierte Port-Lease
     * @param preparer     Ordner-/Template-Vorbereitung
     * @param events       Publisher für Zustandswechsel
     * @param exitListener Empfänger für unerwartete Exits
     */
    public ServiceInstance(ServiceDefinition definition,
                           ServiceRuntime runtime,
                           Supplier<PortLease> ports,
                           ServicePreparer preparer,
                           Consumer<ServiceStateChanged> events,
                           Consumer<ServiceExit> exitListener) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.ports = Objects.requireNonNull(ports, "ports");
        this.preparer = Objects.requireNonNull(preparer, "preparer");
        this.events = Objects.requireNonNull(events, "events");
        this.exitListeners.add(Objects.requireNonNull(exitListener, "exitListener"));
        this.info = ServiceInfo.newBuilder()
                .setId(definition.id().toString())
                .setName(definition.name())
                .setGroup(definition.group().getName())
                .setType(definition.group().getType())
                .setFallback(definition.group().getFallback())
                .setFallbackPriority(definition.group().getFallbackPriority())
                .setHost(HOST)
                .setState(ServiceState.STOPPED)
                .setMaxPlayers(definition.group().getMaxPlayers())
                .setRuntime(definition.options().runtime())
                .putAllProperties(definition.group().getPropertiesMap())
                .build();
    }

    /**
     * Baut den Ordner auf, reserviert einen Port und startet die Runtime.
     *
     * @return der Service im Zustand {@code STARTING}
     * @throws ServiceLifecycleException wenn Vorbereitung, Port oder Start fehlschlagen
     * @throws IllegalStateException     wenn der Service bereits läuft
     */
    public synchronized ServiceInfo start() throws ServiceLifecycleException {
        if (handle != null && handle.isAlive()) {
            throw new IllegalStateException("Service '" + definition.name() + "' läuft bereits");
        }
        ServiceState state = info.getState();
        if (state == ServiceState.PREPARING || state == ServiceState.STARTING
                || state == ServiceState.ONLINE || state == ServiceState.STOPPING) {
            throw new IllegalStateException("Service '" + definition.name() + "' ist im Zustand " + state);
        }

        port = null;
        transition(ServiceState.PREPARING);
        PortLease lease = null;
        try {
            // Vor der Vorbereitung, damit %PORT% bereits im Template steht.
            lease = Objects.requireNonNull(ports.get(), "Port-Lease");
            preparer.prepare(definition, lease.port());
            ServiceLaunch launch = new ServiceLaunch(definition.id(), definition.name(), lease,
                    definition.directory(), definition.platformJar(), definition.bridgeJar(),
                    definition.options(), definition.redis());
            handle = runtime.start(launch);
            port = lease;
            transition(ServiceState.STARTING);
            watchExit(handle);
            return info;
        } catch (IOException | RuntimeException e) {
            if (lease != null) {
                lease.close();
            }
            transition(ServiceState.STOPPED);
            throw new ServiceLifecycleException("Service '" + definition.name()
                    + "' konnte nicht gestartet werden: " + e.getMessage(), e);
        }
    }

    /**
     * Meldet die Bridge als bereit ({@code STARTING} → {@code ONLINE}).
     *
     * @return der Service im Zustand {@code ONLINE}
     * @throws IllegalStateException wenn der Service nicht in {@code STARTING} ist oder nicht läuft
     */
    public synchronized ServiceInfo online() {
        if (info.getState() != ServiceState.STARTING || handle == null || !handle.isAlive()) {
            throw new IllegalStateException("Service '" + definition.name()
                    + "' kann nicht online gehen (Zustand " + info.getState() + ")");
        }
        transition(ServiceState.ONLINE);
        return info;
    }

    /**
     * Leitet einen Konsolenbefehl an den laufenden Service weiter.
     *
     * @param command Befehl ohne Zeilenende
     * @throws ServiceLifecycleException wenn der Service nicht läuft oder die
     *                                   Runtime keine Konsole unterstützt
     */
    public synchronized void sendCommand(String command) throws ServiceLifecycleException {
        Objects.requireNonNull(command, "command");
        if (info.getState() != ServiceState.STARTING && info.getState() != ServiceState.ONLINE) {
            throw new ServiceLifecycleException("Service '" + definition.name()
                    + "' läuft nicht (Zustand " + info.getState() + ")");
        }
        if (handle == null || !handle.isAlive()) {
            throw new ServiceLifecycleException("Service '" + definition.name() + "' läuft nicht mehr");
        }
        if (!(runtime instanceof CommandAware aware)) {
            throw new ServiceLifecycleException("Runtime " + definition.options().runtime()
                    + " von '" + definition.name() + "' unterstützt keine Konsolenbefehle");
        }
        try {
            aware.sendCommand(handle, command);
        } catch (RuntimeException e) {
            throw new ServiceLifecycleException("Befehl an '" + definition.name()
                    + "' nicht zustellbar: " + e.getMessage(), e);
        }
    }

    /**
     * Fährt den Service herunter, gibt den Port frei und räumt dynamische
     * Services auf.
     *
     * @param timeout Graceful-Timeout, bei {@code force} ungenutzt
     * @param force   {@code true} killt sofort
     * @return der Service im Zustand {@code STOPPED}
     * @throws ServiceLifecycleException wenn der Runtime-Stop fehlschlägt
     */
    public synchronized ServiceInfo stop(Duration timeout, boolean force) throws ServiceLifecycleException {
        Objects.requireNonNull(timeout, "timeout");
        if (!force && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout muss positiv sein: " + timeout);
        }
        if (info.getState() == ServiceState.STOPPED) {
            return info;
        }

        transition(ServiceState.STOPPING);
        try {
            if (handle != null && handle.isAlive()) {
                if (force) {
                    runtime.kill(handle);
                } else {
                    runtime.stop(handle, timeout);
                }
            }
        } catch (RuntimeException e) {
            throw new ServiceLifecycleException("Service '" + definition.name()
                    + "' konnte nicht gestoppt werden: " + e.getMessage(), e);
        } finally {
            // Ein Prozess, der den Stop überlebt hat, hält seinen Listener weiter:
            // Die Reservierung bleibt dann bestehen, statt denselben Port einem
            // zweiten Service zu geben, der daraufhin scheitert.
            if (port != null) {
                if (handle == null || !handle.isAlive()) {
                    port.close();
                } else {
                    log.error("Service '{}' läuft nach dem Stop weiter (PID {}) — Port {} bleibt reserviert",
                            definition.name(), handle.nativeId(), port.port());
                }
            }
            cleanup();
            transition(ServiceState.STOPPED);
        }
        return info;
    }

    /**
     * Bricht einen Service ab, der nicht rechtzeitig {@code ONLINE} geworden ist.
     *
     * <p>Der Zustand wird zuerst auf {@code CRASHED} gesetzt, damit der folgende
     * Kill keinen zweiten Crash auslöst; der Port wird freigegeben und der
     * Prozess/Container beendet.
     *
     * @return der Exit für den Crash-Handler, leer wenn der Service nicht mehr in
     *         {@code STARTING} ist
     */
    public synchronized Optional<ServiceExit> startTimedOut() {
        if (info.getState() != ServiceState.STARTING) {
            return Optional.empty();
        }
        return fail("Start-Timeout");
    }

    /**
     * Bricht einen {@code ONLINE} gemeldeten Service ab, dessen Heartbeat
     * ausgeblieben ist (§12.5).
     *
     * @return der Exit für den Crash-Handler, leer wenn der Service nicht
     *         {@code ONLINE} ist
     */
    public synchronized Optional<ServiceExit> heartbeatTimedOut() {
        if (info.getState() != ServiceState.ONLINE) {
            return Optional.empty();
        }
        return fail("Heartbeat-Timeout");
    }

    private Optional<ServiceExit> fail(String reason) {
        List<String> tail = logs();
        ServiceInfo crashed = markCrashed();
        if (handle != null && handle.isAlive()) {
            try {
                runtime.kill(handle);
            } catch (RuntimeException e) {
                log.warn("Service '{}' konnte nach {} nicht gekillt werden: {}",
                        definition.name(), reason, e.toString());
            }
        }
        return Optional.of(new ServiceExit(crashed, definition.directory(), -1, reason, tail));
    }

    /**
     * Markiert einen unerwarteten Exit (Crash) und gibt den Port frei.
     *
     * @return der Service im Zustand {@code CRASHED}
     */
    public synchronized ServiceInfo markCrashed() {
        if (port != null) {
            port.close();
        }
        transition(ServiceState.CRASHED);
        return info;
    }

    /**
     * Wendet Templates erneut an, ohne einen laufenden Service anzufassen.
     *
     * @throws ServiceLifecycleException wenn das erneute Anwenden fehlschlägt
     * @throws IllegalStateException     wenn der Service läuft
     */
    public synchronized void reapply() throws ServiceLifecycleException {
        ServiceState state = info.getState();
        if (state == ServiceState.PREPARING || state == ServiceState.STARTING
                || state == ServiceState.ONLINE || state == ServiceState.STOPPING) {
            throw new IllegalStateException("Service '" + definition.name() + "' läuft (Zustand " + state + ")");
        }
        try {
            // Der Port ist beim Stop freigegeben, den letzten kennt aber noch der Service-Status.
            preparer.reapply(definition, info.getPort());
        } catch (IOException e) {
            throw new ServiceLifecycleException("Templates von '" + definition.name()
                    + "' konnten nicht erneut angewendet werden: " + e.getMessage(), e);
        }
    }

    /**
     * Registriert einen weiteren Empfänger für unerwartete Exits.
     *
     * @param listener Empfänger
     */
    public void onExit(Consumer<ServiceExit> listener) {
        exitListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * @return der aktuelle Service-Zustand
     */
    public synchronized ServiceInfo info() {
        return info;
    }

    /**
     * @return letzte Logzeilen, leer wenn noch nie gestartet
     */
    public synchronized List<String> logs() {
        if (handle == null) {
            return List.of();
        }
        try {
            return runtime.logs(handle);
        } catch (RuntimeException e) {
            log.debug("Logs von '{}' nicht lesbar: {}", definition.name(), e.toString());
            return List.of();
        }
    }

    /**
     * @return die Service-Definition
     */
    public ServiceDefinition definition() {
        return definition;
    }

    private void watchExit(ServiceHandle started) {
        if (!(runtime instanceof ExitAware aware)) {
            return;
        }
        try {
            aware.onExit(started).thenAccept(exitCode -> handleExit(started, exitCode));
        } catch (RuntimeException e) {
            log.warn("Exit von '{}' kann nicht beobachtet werden: {}", definition.name(), e.toString());
        }
    }

    private void handleExit(ServiceHandle source, int exitCode) {
        ServiceExit exit;
        synchronized (this) {
            if (handle != source) {
                return;
            }
            ServiceState state = info.getState();
            if (state != ServiceState.STARTING && state != ServiceState.ONLINE) {
                return;
            }
            exit = new ServiceExit(markCrashed(), definition.directory(), exitCode,
                    "unerwarteter Exit", logs());
        }
        for (Consumer<ServiceExit> listener : exitListeners) {
            try {
                listener.accept(exit);
            } catch (RuntimeException e) {
                log.warn("Crash von '{}' konnte nicht verarbeitet werden: {}", definition.name(), e.toString());
            }
        }
    }

    private void cleanup() {
        if (!definition.dynamic()) {
            return;
        }
        try {
            preparer.cleanup(definition);
        } catch (IOException e) {
            log.warn("Dynamischer Service '{}' konnte nicht vollständig aufgeräumt werden: {}",
                    definition.name(), e.getMessage());
        }
    }

    private void transition(ServiceState next) {
        ServiceState previous = info.getState();
        long startedAt = next == ServiceState.STARTING ? System.currentTimeMillis() : info.getStartedAt();
        info = info.toBuilder()
                .setState(next)
                .setPort(port == null ? 0 : port.port())
                .setStartedAt(startedAt)
                .build();
        try {
            events.accept(ServiceStateChanged.newBuilder()
                    .setService(info)
                    .setPrevious(previous)
                    .build());
        } catch (RuntimeException e) {
            // Ein ausgefallener Event-Pfad darf weder Start noch Stop verhindern.
            log.warn("Zustandswechsel von '{}' nach {} nicht veröffentlicht: {}",
                    definition.name(), next, e.toString());
        }
    }
}
