package dev.bytestorm.node.crash;

import dev.bytestorm.core.config.CrashSettings;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceCrashed;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashHandlerTest {

    @TempDir
    Path temp;

    private final FakeActions actions = new FakeActions();
    private final FakeScheduler scheduler = new FakeScheduler();
    private final List<ServiceCrashed> events = new ArrayList<>();

    private CrashHandler handler(CrashSettings settings) {
        return new CrashHandler(settings, new CrashReportWriter(temp.resolve("crash-reports")),
                events::add, scheduler, actions);
    }

    @Test
    void reportsPublishesAndSchedulesTheRestartAfterBackoff() {
        CrashHandler handler = handler(new CrashSettings(3, List.of(5, 15, 60)));

        CrashHandler.Outcome outcome = handler.crash(service(), temp.resolve("service"), 1,
                "unerwarteter Exit", List.of("last line"));

        assertEquals(CrashHandler.Outcome.RESTART_SCHEDULED, outcome);
        assertEquals(List.of(Duration.ofSeconds(5)), scheduler.delays);
        assertTrue(actions.restarted.isEmpty(), "Neustart darf erst nach dem Backoff laufen");
        assertEquals(1, events.size());

        ServiceCrashed event = events.get(0);
        assertEquals("Lobby-1", event.getService().getName());
        assertEquals(1, event.getExitCode());
        assertEquals("unerwarteter Exit", event.getReason());
        assertEquals(List.of("last line"), event.getLogTailList());
        assertTrue(Files.isDirectory(Path.of(event.getReportPath())));
        assertTrue(Files.isRegularFile(Path.of(event.getReportPath()).resolve("report.txt")));

        scheduler.runAll();
        assertEquals(1, actions.restarted.size());
    }

    @Test
    void afterThreeCrashesTheGroupBecomesDegraded() {
        CrashHandler handler = handler(new CrashSettings(3, List.of(5, 15, 60)));

        for (int i = 0; i < 3; i++) {
            assertEquals(CrashHandler.Outcome.RESTART_SCHEDULED,
                    handler.crash(service(), temp.resolve("service"), 1, "crash", List.of()));
        }
        scheduler.runAll();

        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(60)), scheduler.delays);
        assertEquals(3, actions.restarted.size());

        CrashHandler.Outcome outcome = handler.crash(service(), temp.resolve("service"), 1, "crash", List.of());

        assertEquals(CrashHandler.Outcome.DEGRADED, outcome);
        assertEquals(List.of("Lobby"), actions.degraded);
        assertEquals(4, events.size());
        assertEquals(3, scheduler.delays.size(), "kein weiterer Neustart nach erschöpften Versuchen");
    }

    @Test
    void onlineTimeResetsTheAttempts() {
        CrashHandler handler = handler(new CrashSettings(3, List.of(5)));

        handler.crash(service(), temp.resolve("service"), 1, "crash", List.of());
        assertEquals(1, handler.policy().attempts());

        assertFalse(handler.markOnline(Duration.ofMinutes(4)));
        assertEquals(1, handler.policy().attempts());

        assertTrue(handler.markOnline(Duration.ofMinutes(5)));
        assertEquals(0, handler.policy().attempts());
    }

    private ServiceInfo service() {
        return ServiceInfo.newBuilder()
                .setId("service-id")
                .setName("Lobby-1")
                .setGroup("Lobby")
                .setRuntime(RuntimeType.PROCESS)
                .setState(ServiceState.CRASHED)
                .setPort(30001)
                .build();
    }

    private static final class FakeScheduler implements CrashHandler.Scheduler {

        private final List<Duration> delays = new ArrayList<>();
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void schedule(Runnable task, Duration delay) {
            delays.add(delay);
            tasks.add(task);
        }

        private void runAll() {
            List<Runnable> pending = List.copyOf(tasks);
            tasks.clear();
            pending.forEach(Runnable::run);
        }
    }

    private static final class FakeActions implements CrashHandler.Actions {

        private final List<ServiceInfo> restarted = new ArrayList<>();
        private final List<String> degraded = new ArrayList<>();

        @Override
        public void restart(ServiceInfo service) {
            restarted.add(service);
        }

        @Override
        public void markDegraded(String group) {
            degraded.add(group);
        }
    }
}
