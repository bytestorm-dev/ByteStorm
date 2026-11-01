package dev.bytestorm.cli;

import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.controller.ServiceController;
import dev.bytestorm.node.crash.CrashBudget;
import dev.bytestorm.node.crash.CrashInfo;
import dev.bytestorm.node.lifecycle.ServiceDefinition;
import dev.bytestorm.node.lifecycle.ServiceLifecycleException;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceCommandLineTest {

    private final FakeController controller = new FakeController();
    private final ServiceCommandLine cli = new ServiceCommandLine(controller);

    @Test
    void helpListsTheServiceCommands() {
        String output = cli.execute("help");

        assertTrue(output.contains("service list"));
        assertTrue(output.contains("service stop"));
    }

    @Test
    void infoSummarizesUptimeServicesAndResources() {
        Instant started = Instant.parse("2026-10-23T12:00:00Z");
        ServiceCommandLine fixed = new ServiceCommandLine(controller, null, started,
                Clock.fixed(started.plusSeconds(65), ZoneOffset.UTC));

        String output = fixed.execute("info");

        assertTrue(output.contains("Uptime: 1m 05s"), output);
        assertTrue(output.contains("Services: 1 (0 online)"), output);
        assertTrue(output.contains("Gruppen: 1"), output);
        assertTrue(output.contains("Degradiert: keine"), output);
    }

    @Test
    void formatUptimeHandlesMinutesHoursAndDays() {
        assertEquals("9s", ServiceCommandLine.formatUptime(Duration.ofSeconds(9)));
        assertEquals("2m 03s", ServiceCommandLine.formatUptime(Duration.ofSeconds(123)));
        assertEquals("3h 04m", ServiceCommandLine.formatUptime(Duration.ofMinutes(184)));
        assertEquals("2d 05h", ServiceCommandLine.formatUptime(Duration.ofHours(53)));
    }

    @Test
    void listShowsAllServices() {
        String output = cli.execute("service list");

        assertTrue(output.contains("Lobby-1"));
        assertTrue(output.contains("STARTING"));
        assertTrue(output.contains("30001"));
    }

    @Test
    void stopDelegatesWithTheConfiguredTimeout() {
        String output = cli.execute("service stop Lobby-1");

        assertEquals(List.of("stop:" + controller.info.getId()), controller.calls);
        assertEquals(Duration.ofSeconds(10), controller.stopTimeout);
        assertTrue(output.contains("STOPPED"), output);
    }

    @Test
    void heartbeatAndOnlineAreDelegated() {
        cli.execute("service heartbeat Lobby-1");
        cli.execute("service online Lobby-1");

        assertEquals(List.of("heartbeat:" + controller.info.getId(),
                "online:" + controller.info.getId()), controller.calls);
    }

    @Test
    void infoAndLogsUseTheServiceName() {
        controller.logs = List.of("line one", "line two");

        assertTrue(cli.execute("service info Lobby-1").contains("Lobby"));
        assertTrue(cli.execute("service logs Lobby-1").contains("line two"));
    }

    @Test
    void screenIsOnlyAvailableInTheInteractiveConsole() {
        String output = cli.execute("service screen Lobby-1");

        assertTrue(output.contains("interaktiven Konsole"), output);
        assertTrue(controller.calls.isEmpty());
    }

    @Test
    void execForwardsTheWholeCommand() {
        String output = cli.execute("service exec Lobby-1 say hello world");

        assertEquals(List.of("exec:" + controller.info.getId() + ":say hello world"), controller.calls);
        assertTrue(output.contains("say hello world"), output);
    }

    @Test
    void execWithoutACommandIsRejected() {
        String output = cli.execute("service exec Lobby-1");

        assertTrue(output.contains("Konsolenbefehl fehlt"), output);
        assertTrue(controller.calls.isEmpty());
    }

    @Test
    void restartKillAndReapplyAreDelegated() {
        assertTrue(cli.execute("service restart Lobby-1").contains("STARTING"));
        assertTrue(cli.execute("service kill Lobby-1").contains("STOPPED"));
        assertTrue(cli.execute("service reapply Lobby-1").contains("STARTING"));

        assertEquals(List.of("restart:" + controller.info.getId(),
                "kill:" + controller.info.getId(),
                "reapply:" + controller.info.getId()), controller.calls);
    }

    @Test
    void heartbeatCheckReportsTimedOutServicesByName() {
        controller.timeouts = List.of(ServiceId.parse(controller.info.getId()));

        String output = cli.execute("service heartbeat-check");

        assertEquals(List.of("check"), controller.calls);
        assertTrue(output.contains("Lobby-1"), output);
        assertTrue(output.contains("1"), output);
    }

    @Test
    void heartbeatCheckWithoutTimeoutsSaysSo() {
        String output = cli.execute("service heartbeat-check");

        assertTrue(output.contains("keine Timeouts"), output);
    }

    @Test
    void unknownServicesAndCommandsAreReported() {
        assertEquals("Unbekannter Service: missing", cli.execute("service info missing"));
        assertTrue(cli.execute("service").contains("service list"));
        assertTrue(cli.execute("bogus").contains("Unbekanntes Kommando"));
        assertTrue(cli.execute("service explode Lobby-1").contains("Unbekanntes Service-Kommando"));
    }

    @Test
    void terminalOnlyCommandsExplainWhereTheyRun() {
        assertTrue(cli.execute("clear").contains("interaktiven Konsole"));
        assertTrue(cli.execute("shutdown").contains("interaktiven Konsole"));
        assertTrue(cli.execute("stop").contains("interaktiven Konsole"));
    }

    @Test
    void emptyInputProducesNoOutput() {
        assertEquals("", cli.execute("   "));
    }

    static final class FakeController implements ServiceController {

        ServiceInfo info = ServiceInfo.newBuilder()
                .setId(ServiceId.random().toString())
                .setName("Lobby-1")
                .setGroup("Lobby")
                .setState(ServiceState.STARTING)
                .setRuntime(RuntimeType.PROCESS)
                .setPort(30001)
                .build();
        final List<String> calls = new ArrayList<>();
        List<String> logs = List.of();
        List<CrashInfo> crashes = List.of();
        Map<String, CrashBudget> budgets = Map.of();
        private List<ServiceId> timeouts = List.of();
        private Duration stopTimeout;

        @Override
        public ServiceInfo start(ServiceDefinition definition) {
            calls.add("start-new:" + definition.name());
            return info;
        }

        @Override
        public ServiceInfo start(ServiceId id) throws ServiceLifecycleException {
            calls.add("start:" + id);
            return info;
        }

        @Override
        public ServiceInfo stop(ServiceId id, Duration timeout, boolean force) {
            calls.add("stop:" + id);
            stopTimeout = timeout;
            return info.toBuilder().setState(ServiceState.STOPPED).build();
        }

        @Override
        public ServiceInfo restart(ServiceId id, Duration timeout) {
            calls.add("restart:" + id);
            return info;
        }

        @Override
        public ServiceInfo kill(ServiceId id) {
            calls.add("kill:" + id);
            return info.toBuilder().setState(ServiceState.STOPPED).build();
        }

        @Override
        public ServiceInfo reapply(ServiceId id) {
            calls.add("reapply:" + id);
            return info;
        }

        @Override
        public void sendCommand(ServiceId id, String command) {
            calls.add("exec:" + id + ":" + command);
        }

        @Override
        public ServiceInfo online(ServiceId id) {
            calls.add("online:" + id);
            return info.toBuilder().setState(ServiceState.ONLINE).build();
        }

        @Override
        public void heartbeat(ServiceId id) {
            calls.add("heartbeat:" + id);
        }

        @Override
        public List<ServiceId> checkHeartbeats() {
            calls.add("check");
            return timeouts;
        }

        @Override
        public List<ServiceInfo> services() {
            return List.of(info);
        }

        @Override
        public List<ServiceInfo> byGroup(String group) {
            return List.of(info);
        }

        @Override
        public Optional<ServiceInfo> byName(String name) {
            return info.getName().equals(name) ? Optional.of(info) : Optional.empty();
        }

        @Override
        public List<String> logs(ServiceId id) {
            return logs;
        }

        @Override
        public List<CrashInfo> lastCrashes() {
            return crashes;
        }

        @Override
        public Map<String, CrashBudget> crashBudgets() {
            return budgets;
        }

        @Override
        public Set<String> degradedGroups() {
            return Set.of();
        }
    }
}
