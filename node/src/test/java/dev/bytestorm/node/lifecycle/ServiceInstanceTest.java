package dev.bytestorm.node.lifecycle;

import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.api.service.ServiceHandle;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.node.runtime.PortLease;
import dev.bytestorm.node.runtime.ServiceLaunch;
import dev.bytestorm.node.runtime.ServiceRuntime;
import dev.bytestorm.node.runtime.ServiceRuntimeException;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceStateChanged;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceInstanceTest {

    @TempDir
    Path temp;

    private final FakeRuntime runtime = new FakeRuntime();
    private final FakePreparer preparer = new FakePreparer();
    private final List<PortLease> leases = new ArrayList<>();
    private final List<Integer> releasedPorts = new ArrayList<>();
    private final List<ServiceStateChanged> events = new ArrayList<>();
    private final List<ServiceExit> exits = new ArrayList<>();

    @Test
    void startRunsThroughPreparingToStarting() throws Exception {
        ServiceInstance instance = instance(ServiceMode.DYNAMIC, false);

        var info = instance.start();

        assertEquals(ServiceState.STARTING, info.getState());
        assertEquals(30001, info.getPort());
        assertEquals(1, preparer.prepared);
        assertEquals("Lobby-1", runtime.launch.name());
        assertEquals(30001, runtime.launch.port().port());
        assertEquals(List.of(ServiceState.PREPARING, ServiceState.STARTING),
                events.stream().map(event -> event.getService().getState()).toList());
        assertEquals(List.of(ServiceState.STOPPED, ServiceState.PREPARING),
                events.stream().map(ServiceStateChanged::getPrevious).toList());
        assertFalse(leases.get(0).released());
    }

    @Test
    void onlineComesOnlyFromStarting() throws Exception {
        ServiceInstance instance = instance(ServiceMode.DYNAMIC, false);
        instance.start();

        assertEquals(ServiceState.ONLINE, instance.online().getState());
        assertEquals(ServiceState.ONLINE, events.get(events.size() - 1).getService().getState());
        assertEquals(ServiceState.STARTING, events.get(events.size() - 1).getPrevious());
        assertThrows(IllegalStateException.class, instance::online);
    }

    @Test
    void stopReleasesThePortAndRemovesADynamicDirectory() throws Exception {
        ServiceInstance instance = instance(ServiceMode.DYNAMIC, false);
        instance.start();

        var info = instance.stop(Duration.ofSeconds(5), false);

        assertEquals(ServiceState.STOPPED, info.getState());
        assertTrue(runtime.stopped);
        assertTrue(leases.get(0).released());
        assertEquals(List.of(30001), releasedPorts);
        assertEquals(1, preparer.cleaned);
        assertEquals(ServiceState.STOPPING, events.get(events.size() - 1).getPrevious());
        assertEquals(ServiceState.STOPPED, events.get(events.size() - 1).getService().getState());
    }

    @Test
    void aProcessThatSurvivesTheStopKeepsItsPort() throws Exception {
        runtime.stubborn = true;
        ServiceInstance instance = instance(ServiceMode.DYNAMIC, false);
        instance.start();

        instance.stop(Duration.ofSeconds(5), false);

        assertEquals(ServiceState.STOPPED, instance.info().getState());
        assertFalse(leases.get(0).released(),
                "solange der Prozess läuft, darf der Port nicht neu vergeben werden");
        assertTrue(releasedPorts.isEmpty(), "kein Port wurde freigegeben");
    }

    @Test
    void staticServiceKeepsItsDirectory() throws Exception {
        ServiceInstance instance = instance(ServiceMode.STATIC, false);
        instance.start();

        instance.stop(Duration.ofSeconds(5), false);

        assertEquals(0, preparer.cleaned);
    }

    @Test
    void forceStopKillsTheService() throws Exception {
        ServiceInstance instance = instance(ServiceMode.DYNAMIC, false);
        instance.start();

        instance.stop(Duration.ZERO, true);

        assertTrue(runtime.killed);
        assertFalse(runtime.stopped);
        assertTrue(leases.get(0).released());
    }

    @Test
    void aFailedRuntimeStartReleasesThePort() {
        ServiceInstance instance = instance(ServiceMode.DYNAMIC, true);

        ServiceLifecycleException error = assertThrows(ServiceLifecycleException.class, instance::start);

        assertTrue(error.getMessage().contains("nicht gestartet"), error.getMessage());
        assertTrue(leases.get(0).released());
        assertEquals(ServiceState.STOPPED, instance.info().getState());
    }

    @Test
    void aFailedPreparationLeavesTheServiceStoppedWithoutALeakedPort() {
        preparer.failPrepare = true;
        ServiceInstance instance = instance(ServiceMode.DYNAMIC, false);

        assertThrows(ServiceLifecycleException.class, instance::start);

        // Der Port wird für %PORT% vor der Vorbereitung reserviert und muss bei einem
        // Fehler wieder freigegeben werden.
        assertEquals(1, leases.size());
        assertTrue(leases.get(0).released(), "Port wurde nach fehlgeschlagener Vorbereitung nicht freigegeben");
        assertEquals(ServiceState.STOPPED, instance.info().getState());
    }

    @Test
    void reapplyWorksOnlyWhenStopped() throws Exception {
        ServiceInstance instance = instance(ServiceMode.DYNAMIC, false);

        instance.reapply();
        assertEquals(1, preparer.reapplied);

        instance.start();
        assertThrows(IllegalStateException.class, instance::reapply);

        instance.stop(Duration.ofSeconds(1), false);
        instance.reapply();
        assertEquals(2, preparer.reapplied);
    }

    @Test
    void markCrashedReleasesThePortAndPublishesTheState() throws Exception {
        ServiceInstance instance = instance(ServiceMode.DYNAMIC, false);
        instance.start();

        var info = instance.markCrashed();

        assertEquals(ServiceState.CRASHED, info.getState());
        assertTrue(leases.get(0).released());
        assertEquals(ServiceState.CRASHED, events.get(events.size() - 1).getService().getState());
    }

    @Test
    void logsComeFromTheHandle() throws Exception {
        ServiceInstance instance = instance(ServiceMode.DYNAMIC, false);
        instance.start();

        assertEquals(List.of("console line"), instance.logs());
    }

    @Test
    void anUnexpectedExitMarksCrashedAndNotifiesTheListener() throws Exception {
        ServiceInstance instance = instance(ServiceMode.DYNAMIC, false);
        instance.start();

        runtime.completeExit(1);

        assertEquals(ServiceState.CRASHED, instance.info().getState());
        assertTrue(leases.get(0).released(), "Crash muss den Port freigeben");
        assertEquals(1, exits.size());
        assertEquals(1, exits.get(0).exitCode());
        assertEquals(ServiceState.CRASHED, exits.get(0).info().getState());
        assertEquals(List.of("console line"), exits.get(0).logTail());
        assertEquals(temp.resolve("service").toAbsolutePath().normalize(), exits.get(0).directory());
    }

    @Test
    void anExitAfterStopIsNotTreatedAsCrash() throws Exception {
        ServiceInstance instance = instance(ServiceMode.DYNAMIC, false);
        instance.start();

        instance.stop(Duration.ofSeconds(5), false);
        runtime.completeExit(0);

        assertTrue(exits.isEmpty(), "geplanter Stop darf keinen Crash auslösen");
        assertEquals(ServiceState.STOPPED, instance.info().getState());
    }

    @Test
    void startingTwiceIsRejected() throws Exception {
        ServiceInstance instance = instance(ServiceMode.DYNAMIC, false);
        instance.start();

        assertThrows(IllegalStateException.class, instance::start);
    }

    private ServiceInstance instance(ServiceMode mode, boolean failStart) {
        runtime.failStart = failStart;
        ServiceGroup group = ServiceGroup.newBuilder()
                .setName("Lobby")
                .setMode(mode)
                .setMemory(512)
                .setMaxPlayers(100)
                .putProperties("template", "global")
                .build();
        ServiceDefinition definition = new ServiceDefinition(
                ServiceId.random(), "Lobby-1", group, temp.resolve("service"),
                temp.resolve("server.jar"), null,
                LaunchOptions.builder().memoryMb(512).runtime(RuntimeType.PROCESS).build(),
                new RedisSettings("redis://localhost:6379", "secret"));
        return new ServiceInstance(definition, runtime,
                () -> {
                    PortLease lease = PortLease.of(30001, releasedPorts::add);
                    leases.add(lease);
                    return lease;
                },
                preparer, events::add, exits::add);
    }

    private static final class FakePreparer implements ServicePreparer {

        private int prepared;
        private int reapplied;
        private int cleaned;
        private boolean failPrepare;

        @Override
        public void prepare(ServiceDefinition definition) throws IOException {
            if (failPrepare) {
                throw new IOException("Template kaputt");
            }
            prepared++;
        }

        @Override
        public void reapply(ServiceDefinition definition) {
            reapplied++;
        }

        @Override
        public void cleanup(ServiceDefinition definition) {
            cleaned++;
        }
    }

    private static final class FakeRuntime implements ServiceRuntime, dev.bytestorm.node.runtime.ExitAware {

        private ServiceLaunch launch;
        private boolean alive;
        private boolean stopped;
        private boolean killed;
        private boolean failStart;
        private boolean stubborn;
        private java.util.concurrent.CompletableFuture<Integer> exit;

        @Override
        public ServiceHandle start(ServiceLaunch launch) {
            if (failStart) {
                throw new ServiceRuntimeException(RuntimeType.PROCESS, "kaputt");
            }
            this.launch = launch;
            this.alive = true;
            this.exit = new java.util.concurrent.CompletableFuture<>();
            return new FakeHandle(launch.id(), launch.directory(), this);
        }

        @Override
        public void stop(ServiceHandle handle, Duration timeout) {
            stopped = true;
            if (stubborn) {
                return;
            }
            alive = false;
            exit.complete(0);
        }

        @Override
        public void kill(ServiceHandle handle) {
            killed = true;
            if (stubborn) {
                return;
            }
            alive = false;
            exit.complete(0);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Integer> onExit(ServiceHandle handle) {
            return exit;
        }

        private void completeExit(int code) {
            exit.complete(code);
        }

        @Override
        public boolean isAlive(ServiceHandle handle) {
            return alive;
        }

        @Override
        public List<String> logs(ServiceHandle handle) {
            return List.of("console line");
        }
    }

    private record FakeHandle(ServiceId id, Path directory, FakeRuntime runtime) implements ServiceHandle {

        @Override
        public String nativeId() {
            return "fake";
        }

        @Override
        public boolean isAlive() {
            return runtime.alive;
        }
    }
}
