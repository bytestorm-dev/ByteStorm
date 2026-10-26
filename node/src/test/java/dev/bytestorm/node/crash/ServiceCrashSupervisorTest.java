package dev.bytestorm.node.crash;

import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.api.service.ServiceHandle;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.CrashSettings;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.node.lifecycle.ServiceDefinition;
import dev.bytestorm.node.lifecycle.ServiceInstance;
import dev.bytestorm.node.lifecycle.ServicePreparer;
import dev.bytestorm.node.runtime.ExitAware;
import dev.bytestorm.node.runtime.PortLease;
import dev.bytestorm.node.runtime.ServiceLaunch;
import dev.bytestorm.node.runtime.ServiceRuntime;
import dev.bytestorm.node.runtime.ServiceRuntimeException;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceCrashed;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceCrashSupervisorTest {

    @TempDir
    Path temp;

    private final FakeRuntime runtime = new FakeRuntime();
    private final FakeScheduler scheduler = new FakeScheduler();
    private final List<ServiceCrashed> crashedEvents = new ArrayList<>();
    private final List<String> degraded = new ArrayList<>();
    private final List<PortLease> leases = new ArrayList<>();

    @Test
    void crashWritesReportPublishesEventAndRestartsAfterBackoff() throws Exception {
        ServiceCrashSupervisor supervisor = supervisor(new CrashSettings(3, List.of(5, 15, 60)));

        supervisor.start();
        runtime.completeExit(1);

        assertEquals(ServiceState.CRASHED, supervisor.instance().info().getState());
        assertEquals(1, crashedEvents.size());
        assertEquals(1, crashedEvents.get(0).getExitCode());
        assertEquals("unerwarteter Exit", crashedEvents.get(0).getReason());
        assertEquals(List.of(Duration.ofSeconds(5)), scheduler.backoffs());
        assertTrue(Files.isDirectory(Path.of(crashedEvents.get(0).getReportPath())));
        assertEquals(1, runtime.starts, "Neustart darf erst nach dem Backoff laufen");

        scheduler.runWithDelay(Duration.ofSeconds(5));

        assertEquals(2, runtime.starts);
        assertEquals(ServiceState.STARTING, supervisor.instance().info().getState());
    }

    @Test
    void afterThreeRestartsTheGroupIsDegraded() throws Exception {
        ServiceCrashSupervisor supervisor = supervisor(new CrashSettings(3, List.of(5, 15, 60)));
        supervisor.start();

        long[] backoffs = {5, 15, 60};
        for (int attempt = 1; attempt <= 3; attempt++) {
            runtime.completeExit(attempt);
            assertEquals(ServiceState.CRASHED, supervisor.instance().info().getState());
            scheduler.runWithDelay(Duration.ofSeconds(backoffs[attempt - 1]));
        }
        runtime.completeExit(4);

        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(60)),
                scheduler.backoffs());
        assertEquals(4, crashedEvents.size());
        assertEquals(List.of("Lobby"), degraded);
        assertEquals(ServiceState.CRASHED, supervisor.instance().info().getState());
        assertEquals(4, runtime.starts, "nach erschöpften Versuchen kein weiterer Start");
    }

    @Test
    void aManualStopCancelsThePendingRestart() throws Exception {
        ServiceCrashSupervisor supervisor = supervisor(new CrashSettings(3, List.of(5)));
        supervisor.start();
        runtime.completeExit(1);

        supervisor.stop(Duration.ofSeconds(1), false);
        assertEquals(ServiceState.STOPPED, supervisor.instance().info().getState());

        scheduler.runWithDelay(Duration.ofSeconds(5));

        assertEquals(1, runtime.starts, "ein gestoppter Service darf nicht wieder anlaufen");
        assertEquals(ServiceState.STOPPED, supervisor.instance().info().getState());
    }

    @Test
    void aStartTimeoutIsReportedAsCrashAndRestarted() throws Exception {
        ServiceCrashSupervisor supervisor = supervisor(new CrashSettings(3, List.of(5)));
        supervisor.start();

        scheduler.runWithDelay(Duration.ofSeconds(30));

        assertEquals(ServiceState.CRASHED, supervisor.instance().info().getState());
        assertEquals(1, crashedEvents.size());
        assertEquals("Start-Timeout", crashedEvents.get(0).getReason());
        assertEquals(-1, crashedEvents.get(0).getExitCode());
        assertTrue(runtime.killed);
        assertEquals(List.of(Duration.ofSeconds(5)), scheduler.backoffs());
        assertTrue(Files.isDirectory(Path.of(crashedEvents.get(0).getReportPath())));
    }

    @Test
    void onlineBeforeTheTimeoutInvalidatesIt() throws Exception {
        ServiceCrashSupervisor supervisor = supervisor(new CrashSettings(3, List.of(5)));
        supervisor.start();
        supervisor.online();

        scheduler.runWithDelay(Duration.ofSeconds(30));

        assertEquals(ServiceState.ONLINE, supervisor.instance().info().getState());
        assertTrue(crashedEvents.isEmpty());
        assertFalse(runtime.killed);
    }

    @Test
    void fiveOnlineMinutesResetTheAttempts() throws Exception {
        ServiceCrashSupervisor supervisor = supervisor(new CrashSettings(3, List.of(5)));
        supervisor.start();
        runtime.completeExit(1);
        scheduler.runWithDelay(Duration.ofSeconds(5));
        supervisor.online();

        assertEquals(1, supervisor.crashHandler().policy().attempts());

        scheduler.runWithDelay(RestartPolicy.DEFAULT_ONLINE_RESET);

        assertEquals(0, supervisor.crashHandler().policy().attempts());
    }

    @Test
    void aFailedRestartMarksTheGroupDegraded() throws Exception {
        ServiceCrashSupervisor supervisor = supervisor(new CrashSettings(3, List.of(5)));
        supervisor.start();
        runtime.completeExit(1);
        runtime.failStart = true;

        scheduler.runWithDelay(Duration.ofSeconds(5));

        assertEquals(List.of("Lobby"), degraded);
        assertFalse(degraded.isEmpty());
    }

    private ServiceCrashSupervisor supervisor(CrashSettings settings) {
        ServiceGroup group = ServiceGroup.newBuilder()
                .setName("Lobby")
                .setMode(ServiceMode.DYNAMIC)
                .setMaxPlayers(100)
                .build();
        ServiceDefinition definition = new ServiceDefinition(ServiceId.random(), "Lobby-1", group,
                temp.resolve("service"), temp.resolve("server.jar"), null,
                LaunchOptions.builder().memoryMb(512).runtime(RuntimeType.PROCESS).build(),
                new RedisSettings("redis://localhost:6379", "secret"));
        ServiceInstance instance = new ServiceInstance(definition, runtime,
                () -> {
                    PortLease lease = PortLease.fixed(30001);
                    leases.add(lease);
                    return lease;
                },
                new ServicePreparer() {
                    @Override
                    public void prepare(ServiceDefinition ignored) {
                    }
                }, event -> {
        });
        return new ServiceCrashSupervisor(instance, settings,
                new CrashReportWriter(temp.resolve("crash-reports")),
                crashedEvents::add, scheduler, degraded::add,
                Duration.ofSeconds(30), RestartPolicy.DEFAULT_ONLINE_RESET);
    }

    private static final class FakeScheduler implements CrashHandler.Scheduler {

        private final List<Task> tasks = new ArrayList<>();
        private final List<Duration> recorded = new ArrayList<>();

        @Override
        public void schedule(Runnable task, Duration delay) {
            recorded.add(delay);
            tasks.add(new Task(delay, task));
        }

        private List<Duration> backoffs() {
            return recorded.stream()
                    .filter(delay -> !delay.equals(Duration.ofSeconds(30)))
                    .filter(delay -> !delay.equals(RestartPolicy.DEFAULT_ONLINE_RESET))
                    .toList();
        }

        private void runWithDelay(Duration delay) {
            Deque<Task> matching = new ArrayDeque<>();
            for (Task task : tasks) {
                if (task.delay().equals(delay)) {
                    matching.add(task);
                }
            }
            tasks.removeAll(matching);
            matching.forEach(task -> task.task().run());
        }

        private record Task(Duration delay, Runnable task) {
        }
    }

    private static final class FakeRuntime implements ServiceRuntime, ExitAware {

        private final List<FakeHandle> handles = new ArrayList<>();
        private boolean alive;
        private boolean killed;
        private boolean failStart;
        private int starts;

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
            killed = true;
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
