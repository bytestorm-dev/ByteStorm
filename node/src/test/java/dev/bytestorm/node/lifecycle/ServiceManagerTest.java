package dev.bytestorm.node.lifecycle;

import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.api.service.ServiceHandle;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.CrashSettings;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.node.crash.CrashHandler;
import dev.bytestorm.node.crash.CrashReportWriter;
import dev.bytestorm.node.crash.CrashSupport;
import dev.bytestorm.node.runtime.CommandAware;
import dev.bytestorm.node.runtime.ExitAware;
import dev.bytestorm.node.runtime.PortLease;
import dev.bytestorm.node.runtime.ServiceLaunch;
import dev.bytestorm.node.runtime.ServiceRuntime;
import dev.bytestorm.node.runtime.ServiceRuntimeException;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceManagerTest {

    @TempDir
    Path temp;

    private final FakeRuntime runtime = new FakeRuntime();
    private final FakeScheduler scheduler = new FakeScheduler();
    private final List<PortLease> leases = new ArrayList<>();
    private final List<String> degraded = new ArrayList<>();
    private final List<dev.bytestorm.proto.ServiceCrashed> crashedEvents = new ArrayList<>();

    @Test
    void startRegistersTheServiceAndStopRemovesIt() throws Exception {
        ServiceManager manager = manager(new CrashSettings(3, List.of(5)));
        ServiceDefinition definition = definition("Lobby-1");

        var info = manager.start(definition);

        assertEquals(ServiceState.STARTING, info.getState());
        assertEquals(1, manager.all().size());
        assertEquals(1, manager.byGroup("Lobby").size());
        assertTrue(manager.info(definition.id()).isPresent());
        assertTrue(manager.supervisor(definition.id()).isPresent());
        assertEquals(List.of("console line"), manager.logs(definition.id()));

        manager.online(definition.id());
        assertEquals(ServiceState.ONLINE, manager.info(definition.id()).orElseThrow().getState());

        var stopped = manager.stop("Lobby-1", Duration.ofSeconds(2), false);
        assertEquals(ServiceState.STOPPED, stopped.getState());
        assertTrue(leases.get(0).released());
        assertTrue(manager.remove(definition.id()));
        assertTrue(manager.all().isEmpty());
    }

    @Test
    void consoleCommandsAreForwardedOnlyWhileRunning() throws Exception {
        ServiceManager manager = manager(new CrashSettings(3, List.of(5)));
        ServiceDefinition definition = definition("Lobby-1");
        manager.start(definition);

        manager.sendCommand(definition.id(), "say hello");
        assertEquals(List.of("say hello"), runtime.commands);

        manager.stop(definition.id(), Duration.ofSeconds(1), false);
        assertThrows(ServiceLifecycleException.class,
                () -> manager.sendCommand(definition.id(), "late"));
    }

    @Test
    void restartKillAndReapplyUseTheLifecycle() throws Exception {
        ServiceManager manager = manager(new CrashSettings(3, List.of(5)));
        ServiceDefinition definition = definition("Lobby-1");
        manager.start(definition);

        var restarted = manager.restart(definition.id(), Duration.ofSeconds(1));
        assertEquals(ServiceState.STARTING, restarted.getState());
        assertEquals(2, runtime.starts);

        var killed = manager.kill(definition.id());
        assertEquals(ServiceState.STOPPED, killed.getState());
        assertEquals(1, runtime.kills);

        var reapplied = manager.reapply(definition.id());
        assertEquals(ServiceState.STOPPED, reapplied.getState());
    }

    @Test
    void reapplyOnARunningServiceIsRejected() throws Exception {
        ServiceManager manager = manager(new CrashSettings(3, List.of(5)));
        ServiceDefinition definition = definition("Lobby-1");
        manager.start(definition);
        manager.online(definition.id());

        assertThrows(IllegalStateException.class, () -> manager.reapply(definition.id()));
    }

    @Test
    void duplicateNamesAndIdsAreRejected() throws Exception {
        ServiceManager manager = manager(new CrashSettings(3, List.of(5)));
        ServiceDefinition first = definition("Lobby-1");
        manager.start(first);

        IllegalStateException byName = assertThrows(IllegalStateException.class,
                () -> manager.start(definition("Lobby-1")));
        assertTrue(byName.getMessage().contains("bereits registriert"), byName.getMessage());

        IllegalStateException byId = assertThrows(IllegalStateException.class,
                () -> manager.start(new ServiceDefinition(first.id(), "Lobby-2", first.group(),
                        temp.resolve("other"), first.platformJar(), null, first.options(), first.redis())));
        assertTrue(byId.getMessage().contains("bereits registriert"), byId.getMessage());
        assertEquals(1, manager.all().size());
    }

    @Test
    void aFailedStartLeavesNoRegistration() {
        runtime.failStart = true;
        ServiceManager manager = manager(new CrashSettings(3, List.of(5)));
        ServiceDefinition definition = definition("Lobby-1");

        assertThrows(ServiceLifecycleException.class, () -> manager.start(definition));

        assertTrue(manager.all().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> manager.start("Lobby-1"));
    }

    @Test
    void aRunningServiceCannotBeRemoved() throws Exception {
        ServiceManager manager = manager(new CrashSettings(3, List.of(5)));
        ServiceDefinition definition = definition("Lobby-1");
        manager.start(definition);

        assertThrows(IllegalStateException.class, () -> manager.remove(definition.id()));
    }

    @Test
    void unknownServicesAreRejected() {
        ServiceManager manager = manager(new CrashSettings(3, List.of(5)));

        assertThrows(IllegalArgumentException.class,
                () -> manager.stop(ServiceId.random(), Duration.ofSeconds(1), false));
        assertThrows(IllegalArgumentException.class,
                () -> manager.start("missing"));
        assertThrows(IllegalArgumentException.class,
                () -> manager.logs(ServiceId.random()));
    }

    @Test
    void crashRestartsAndThenMarksTheGroupDegraded() throws Exception {
        ServiceManager manager = manager(new CrashSettings(1, List.of(0)));
        ServiceDefinition definition = definition("Lobby-1");
        manager.start(definition);

        runtime.completeExit(1);
        scheduler.runWithDelay(Duration.ZERO);

        assertEquals(ServiceState.STARTING, manager.info(definition.id()).orElseThrow().getState());
        assertEquals(2, runtime.starts);

        runtime.completeExit(1);

        assertEquals(ServiceState.CRASHED, manager.info(definition.id()).orElseThrow().getState());
        assertTrue(manager.degradedGroups().contains("Lobby"));
        assertEquals(List.of("Lobby"), degraded);
    }

    @Test
    void aMissingHeartbeatIsTreatedAsCrash() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-23T12:00:00Z"));
        ServiceManager manager = manager(new CrashSettings(1, List.of(0)), Duration.ofSeconds(10), clock);
        ServiceDefinition definition = definition("Lobby-1");
        manager.start(definition);
        manager.online(definition.id());

        clock.advance(Duration.ofSeconds(9));
        assertTrue(manager.checkHeartbeats().isEmpty());

        clock.advance(Duration.ofSeconds(2));
        assertEquals(List.of(definition.id()), manager.checkHeartbeats());

        assertEquals(ServiceState.CRASHED, manager.info(definition.id()).orElseThrow().getState());
        assertTrue(leases.get(0).released());
        assertEquals(1, crashedEvents.size());
        assertEquals("Heartbeat-Timeout", crashedEvents.get(0).getReason());
        assertEquals(1, crashedEvents.get(0).getLogTailCount());
    }

    @Test
    void aHeartbeatExtendsTheDeadline() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-23T12:00:00Z"));
        ServiceManager manager = manager(new CrashSettings(1, List.of(0)), Duration.ofSeconds(10), clock);
        ServiceDefinition definition = definition("Lobby-1");
        manager.start(definition);
        manager.online(definition.id());

        clock.advance(Duration.ofSeconds(9));
        manager.heartbeat("Lobby-1");
        clock.advance(Duration.ofSeconds(9));

        assertTrue(manager.checkHeartbeats().isEmpty());

        clock.advance(Duration.ofSeconds(2));
        assertEquals(List.of(definition.id()), manager.checkHeartbeats());
    }

    @Test
    void stoppedServicesAreNoLongerWatched() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-23T12:00:00Z"));
        ServiceManager manager = manager(new CrashSettings(1, List.of(0)), Duration.ofSeconds(10), clock);
        ServiceDefinition definition = definition("Lobby-1");
        manager.start(definition);
        manager.online(definition.id());
        manager.stop(definition.id(), Duration.ofSeconds(1), false);

        clock.advance(Duration.ofMinutes(5));

        assertTrue(manager.checkHeartbeats().isEmpty());
        assertTrue(crashedEvents.isEmpty());
    }

    @Test
    void stopAllStopsEveryService() throws Exception {
        ServiceManager manager = manager(new CrashSettings(3, List.of(5)));
        manager.start(definition("Lobby-1"));
        manager.start(definition("Lobby-2"));

        manager.stopAll(Duration.ofSeconds(2));

        assertTrue(manager.all().stream().allMatch(info -> info.getState() == ServiceState.STOPPED));
        assertTrue(leases.stream().allMatch(PortLease::released));
    }

    @Test
    void closeStopsEverythingAndClearsTheRegistry() throws Exception {
        ServiceManager manager = manager(new CrashSettings(3, List.of(5)));
        manager.start(definition("Lobby-1"));

        manager.close();

        assertTrue(manager.all().isEmpty());
        assertTrue(leases.get(0).released());
        assertFalse(runtime.alive);
    }

    private ServiceManager manager(CrashSettings settings) {
        return manager(settings, ServiceManager.DEFAULT_HEARTBEAT_TIMEOUT, Clock.systemUTC());
    }

    private ServiceManager manager(CrashSettings settings, Duration heartbeatTimeout, Clock clock) {
        return new ServiceManager(
                group -> runtime,
                (group, name) -> {
                    PortLease lease = PortLease.fixed(30001);
                    leases.add(lease);
                    return lease;
                },
                definition -> {
                },
                event -> {
                },
                new CrashSupport(settings, new CrashReportWriter(temp.resolve("crash-reports")),
                        crashedEvents::add, scheduler, degraded::add), heartbeatTimeout, clock);
    }

    private ServiceDefinition definition(String name) {
        ServiceGroup group = ServiceGroup.newBuilder()
                .setName("Lobby")
                .setMode(ServiceMode.DYNAMIC)
                .setMaxPlayers(100)
                .build();
        Path directory = temp.resolve(name);
        return new ServiceDefinition(ServiceId.random(), name, group, directory,
                temp.resolve("server.jar"), null,
                LaunchOptions.builder().memoryMb(512).runtime(RuntimeType.PROCESS).build(),
                new RedisSettings("redis://localhost:6379", "secret"));
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class FakeScheduler implements CrashHandler.Scheduler {

        private final List<Task> tasks = new ArrayList<>();

        @Override
        public void schedule(Runnable task, Duration delay) {
            tasks.add(new Task(delay, task));
        }

        private void runWithDelay(Duration delay) {
            List<Task> matching = tasks.stream().filter(task -> task.delay().equals(delay)).toList();
            tasks.removeAll(matching);
            matching.forEach(task -> task.task().run());
        }

        private record Task(Duration delay, Runnable task) {
        }
    }

    private static final class FakeRuntime implements ServiceRuntime, ExitAware, CommandAware {

        private final List<FakeHandle> handles = new ArrayList<>();
        private final List<String> commands = new ArrayList<>();
        private boolean alive;
        private boolean failStart;
        private int starts;
        private int kills;

        @Override
        public ServiceHandle start(ServiceLaunch launch) {
            if (failStart) {
                throw new ServiceRuntimeException(RuntimeType.PROCESS, "kaputt");
            }
            starts++;
            alive = true;
            FakeHandle handle = new FakeHandle(launch.id(), launch.directory(), new CompletableFuture<>(), this);
            handles.add(handle);
            return handle;
        }

        @Override
        public void stop(ServiceHandle handle, Duration timeout) {
            alive = false;
            ((FakeHandle) handle).exit().complete(0);
        }

        @Override
        public void kill(ServiceHandle handle) {
            kills++;
            alive = false;
            ((FakeHandle) handle).exit().complete(0);
        }

        @Override
        public boolean isAlive(ServiceHandle handle) {
            return alive;
        }

        @Override
        public List<String> logs(ServiceHandle handle) {
            return List.of("console line");
        }

        @Override
        public CompletableFuture<Integer> onExit(ServiceHandle handle) {
            return ((FakeHandle) handle).exit();
        }

        @Override
        public void sendCommand(ServiceHandle handle, String command) {
            commands.add(command);
        }

        private void completeExit(int code) {
            FakeHandle last = handles.get(handles.size() - 1);
            alive = false;
            last.exit().complete(code);
        }
    }

    private record FakeHandle(ServiceId id, Path directory, CompletableFuture<Integer> exit,
                              FakeRuntime runtime) implements ServiceHandle {

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
