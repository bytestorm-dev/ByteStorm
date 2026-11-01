package dev.bytestorm.controller.scaling;

import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.ScalingSettings;
import dev.bytestorm.node.lifecycle.ServiceLifecycleException;
import dev.bytestorm.proto.LoadRule;
import dev.bytestorm.proto.PlayerCountRule;
import dev.bytestorm.proto.ScheduleRule;
import dev.bytestorm.proto.ScalingRule;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalingManagerTest {

    private final List<ServiceGroup> groups = new ArrayList<>();
    private final List<ServiceInfo> services = new ArrayList<>();
    private final FakeActions actions = new FakeActions(services);
    private final FakeHostMetrics host = new FakeHostMetrics();
    private final Set<String> degraded = new HashSet<>();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-23T18:30:00Z"));

    @Test
    void startsTheMinimumOneServicePerCycle() {
        groups.add(group("Lobby", 2, 4, ServiceMode.DYNAMIC, null));
        ScalingManager manager = manager();

        manager.tick();
        assertEquals(1, actions.starts.size());

        manager.tick();
        assertEquals(2, actions.starts.size());

        manager.tick();
        assertEquals(2, actions.starts.size());
    }

    @Test
    void aDegradedGroupGetsNoNewServices() {
        groups.add(group("Lobby", 1, 4, ServiceMode.DYNAMIC, null));
        degraded.add("Lobby");
        ScalingManager manager = manager();

        List<ScalingDecision> decisions = manager.tick();

        assertTrue(actions.starts.isEmpty(),
                "eine degradierte Gruppe darf nicht automatisch einen neuen Service bekommen");
        assertEquals(1, decisions.size());
        assertEquals(ScalingDecision.Action.NONE, decisions.get(0).action());
        assertEquals("degraded", decisions.get(0).rule());
    }

    @Test
    void stopsExcessDynamicServicesWithoutPlayers() {
        groups.add(group("Lobby", 0, 1, ServiceMode.DYNAMIC, null));
        services.add(service("Lobby", "Lobby-1", 0));
        services.add(service("Lobby", "Lobby-2", 0));
        services.add(service("Lobby", "Lobby-3", 0));
        ScalingManager manager = manager();

        manager.tick();

        assertEquals(2, actions.stops.size());
        assertEquals(1, services.stream().filter(info -> info.getState() == ServiceState.ONLINE).count());
    }

    @Test
    void staticServicesAreNeverStoppedByTheMaximumRule() {
        groups.add(group("Proxy", 0, 1, ServiceMode.STATIC, null));
        services.add(service("Proxy", "Proxy-1", 0));
        services.add(service("Proxy", "Proxy-2", 0));
        ScalingManager manager = manager();

        manager.tick();

        assertTrue(actions.stops.isEmpty());
        assertEquals(2, services.stream().filter(info -> info.getState() == ServiceState.ONLINE).count());
    }

    @Test
    void servicesWithPlayersAreNeverStopped() {
        groups.add(group("Lobby", 0, 1, ServiceMode.DYNAMIC, null));
        services.add(service("Lobby", "Lobby-1", 5));
        services.add(service("Lobby", "Lobby-2", 0));
        ScalingManager manager = manager();

        manager.tick();

        assertEquals(List.of("Lobby-2"), actions.stops);
        assertEquals(ServiceState.ONLINE, services.get(0).getState());
        assertEquals("Lobby-1", services.get(0).getName());
    }

    @Test
    void playerCountScalesUpAndTheCooldownBlocksTheNextStart() {
        PlayerCountRule rule = PlayerCountRule.newBuilder()
                .setScaleUpUtilization(0.8)
                .setScaleUpServices(1)
                .build();
        groups.add(group("Lobby", 1, 3, ServiceMode.DYNAMIC, rule));
        services.add(service("Lobby", "Lobby-1", 90));
        actions.startPlayers = 90;
        ScalingManager manager = manager();

        manager.tick();
        assertEquals(1, actions.starts.size());

        manager.tick();
        assertEquals(1, actions.starts.size());

        clock.advance(Duration.ofSeconds(31));
        manager.tick();
        assertEquals(2, actions.starts.size());
    }

    @Test
    void playerCountDoesNotStartBeyondTheMaximum() {
        PlayerCountRule rule = PlayerCountRule.newBuilder()
                .setScaleUpUtilization(0.8)
                .setScaleUpServices(3)
                .build();
        groups.add(group("Lobby", 1, 2, ServiceMode.DYNAMIC, rule));
        services.add(service("Lobby", "Lobby-1", 90));
        services.add(service("Lobby", "Lobby-2", 90));
        ScalingManager manager = manager();

        manager.tick();

        assertTrue(actions.starts.isEmpty());
    }

    @Test
    void idleServicesAreStoppedOnlyAfterTheConfiguredMinutes() {
        PlayerCountRule rule = PlayerCountRule.newBuilder()
                .setScaleUpUtilization(0.8)
                .setScaleUpServices(1)
                .setIdleUtilization(0.1)
                .setIdleMinutes(5)
                .build();
        groups.add(group("Lobby", 1, 3, ServiceMode.DYNAMIC, rule));
        services.add(service("Lobby", "Lobby-1", 0));
        services.add(service("Lobby", "Lobby-2", 0));
        ScalingManager manager = manager();

        manager.tick();
        assertTrue(actions.stops.isEmpty());

        clock.advance(Duration.ofMinutes(4));
        manager.tick();
        assertTrue(actions.stops.isEmpty());

        clock.advance(Duration.ofMinutes(1));
        manager.tick();
        assertEquals(1, actions.stops.size());
        assertEquals(1, services.stream().filter(info -> info.getState() == ServiceState.ONLINE).count());
    }

    @Test
    void playerActivityRestartsTheIdleTimer() {
        PlayerCountRule rule = PlayerCountRule.newBuilder()
                .setScaleUpUtilization(0.8)
                .setScaleUpServices(1)
                .setIdleUtilization(0.1)
                .setIdleMinutes(5)
                .build();
        groups.add(group("Lobby", 1, 3, ServiceMode.DYNAMIC, rule));
        services.add(service("Lobby", "Lobby-1", 0));
        services.add(service("Lobby", "Lobby-2", 0));
        ScalingManager manager = manager();

        manager.tick();
        clock.advance(Duration.ofMinutes(4));
        services.set(0, services.get(0).toBuilder().setOnlinePlayers(50).build());
        manager.tick();

        services.set(0, services.get(0).toBuilder().setOnlinePlayers(0).build());
        manager.tick();
        assertTrue(actions.stops.isEmpty());

        clock.advance(Duration.ofMinutes(5));
        manager.tick();
        assertEquals(1, actions.stops.size());
    }

    @Test
    void maintenanceGroupsAreIgnored() {
        groups.add(group("Lobby", 2, 4, ServiceMode.DYNAMIC, null).toBuilder()
                .setMaintenance(true)
                .build());
        ScalingManager manager = manager();

        manager.tick();

        assertTrue(actions.starts.isEmpty());
    }

    @Test
    void scheduleMinimumStartsServicesInsideTheWindow() {
        groups.add(withRule(group("Lobby", 1, 5, ServiceMode.DYNAMIC, null),
                schedule("* 18-23 * * *", "UTC", 3, 5)));
        ScalingManager manager = manager();

        List<ScalingDecision> first = manager.tick();
        assertTrue(first.stream().anyMatch(decision -> decision.action() == ScalingDecision.Action.START
                && decision.rule().equals("schedule")), first.toString());

        manager.tick();
        manager.tick();
        assertEquals(3, actions.starts.size());
    }

    @Test
    void scheduleMaximumStopsExcessServices() {
        groups.add(withRule(group("Lobby", 0, 5, ServiceMode.DYNAMIC, null),
                schedule("* 18-23 * * *", "UTC", 0, 2)));
        services.add(service("Lobby", "Lobby-1", 0));
        services.add(service("Lobby", "Lobby-2", 0));
        services.add(service("Lobby", "Lobby-3", 0));
        services.add(service("Lobby", "Lobby-4", 0));
        ScalingManager manager = manager();

        manager.tick();

        assertEquals(2, actions.stops.size());
        assertEquals(2, services.stream().filter(info -> info.getState() == ServiceState.ONLINE).count());
    }

    @Test
    void scheduleOutsideTheWindowFallsBackToTheGroupMinimum() {
        groups.add(withRule(group("Lobby", 1, 5, ServiceMode.DYNAMIC, null),
                schedule("* 8-9 * * *", "UTC", 3, 5)));
        ScalingManager manager = manager();

        manager.tick();
        manager.tick();
        manager.tick();

        assertEquals(1, actions.starts.size());
    }

    @Test
    void scheduleUsesTheConfiguredTimezone() {
        groups.add(withRule(group("Lobby", 1, 5, ServiceMode.DYNAMIC, null),
                schedule("* 14 * * *", "America/New_York", 2, 5)));
        ScalingManager manager = manager();

        manager.tick();

        assertEquals(1, actions.starts.size());
    }

    @Test
    void hostAboveTheLoadThresholdBlocksStarts() {
        host.cpu = 0.9;
        groups.add(withRule(group("Lobby", 0, 3, ServiceMode.DYNAMIC, null),
                load(50, 90, 2)));
        ScalingManager manager = manager();

        List<ScalingDecision> decisions = manager.tick();

        assertTrue(actions.starts.isEmpty());
        assertTrue(decisions.stream().anyMatch(decision -> decision.action() == ScalingDecision.Action.NONE
                && decision.rule().equals("load")), decisions.toString());
    }

    @Test
    void hostBelowTheLoadThresholdAllowsTheLoadMinimum() {
        host.cpu = 0.1;
        groups.add(withRule(group("Lobby", 0, 3, ServiceMode.DYNAMIC, null),
                load(50, 90, 2)));
        ScalingManager manager = manager();

        manager.tick();
        manager.tick();

        assertEquals(2, actions.starts.size());
    }

    @Test
    void hostAboveTheLoadThresholdBlocksPlayerCountScaling() {
        host.memory = 0.95;
        PlayerCountRule player = PlayerCountRule.newBuilder()
                .setScaleUpUtilization(0.8)
                .setScaleUpServices(1)
                .build();
        groups.add(withRule(group("Lobby", 1, 3, ServiceMode.DYNAMIC, player),
                load(90, 80, 1)));
        services.add(service("Lobby", "Lobby-1", 90));
        ScalingManager manager = manager();

        List<ScalingDecision> decisions = manager.tick();

        assertTrue(actions.starts.isEmpty());
        assertTrue(decisions.stream().anyMatch(decision -> decision.action() == ScalingDecision.Action.NONE
                && decision.rule().equals("load")), decisions.toString());
    }

    @Test
    void explainReportsStartsWithoutStarting() {
        groups.add(group("Lobby", 2, 4, ServiceMode.DYNAMIC, null));
        ScalingManager manager = manager();

        List<ScalingDecision> decisions = manager.explain();

        assertTrue(actions.starts.isEmpty());
        assertTrue(decisions.stream().anyMatch(decision -> decision.action() == ScalingDecision.Action.START),
                decisions.toString());
    }

    @Test
    void explainReportsStopsWithoutStopping() {
        groups.add(group("Lobby", 0, 1, ServiceMode.DYNAMIC, null));
        services.add(service("Lobby", "Lobby-1", 0));
        services.add(service("Lobby", "Lobby-2", 0));
        ScalingManager manager = manager();

        List<ScalingDecision> decisions = manager.explain();

        assertTrue(actions.stops.isEmpty());
        assertTrue(decisions.stream().anyMatch(decision -> decision.action() == ScalingDecision.Action.STOP),
                decisions.toString());
    }

    @Test
    void explainDoesNotConsumeTheCooldown() {
        PlayerCountRule rule = PlayerCountRule.newBuilder()
                .setScaleUpUtilization(0.8)
                .setScaleUpServices(1)
                .build();
        groups.add(group("Lobby", 1, 3, ServiceMode.DYNAMIC, rule));
        services.add(service("Lobby", "Lobby-1", 90));
        ScalingManager manager = manager();

        List<ScalingDecision> first = manager.explain();
        List<ScalingDecision> second = manager.explain();

        assertTrue(actions.starts.isEmpty());
        assertTrue(first.stream().anyMatch(decision -> decision.action() == ScalingDecision.Action.START));
        assertTrue(second.stream().anyMatch(decision -> decision.action() == ScalingDecision.Action.START));
    }

    @Test
    void failedStartsAreBackedOff() {
        groups.add(group("Lobby", 1, 3, ServiceMode.DYNAMIC, null));
        actions.failStarts = true;
        ScalingManager manager = manager();

        manager.tick();
        manager.tick();
        assertEquals(1, actions.attempts);

        clock.advance(Duration.ofSeconds(31));
        manager.tick();
        assertEquals(2, actions.attempts);
    }

    @Test
    void manualScaleStartsUpToTheTarget() {
        groups.add(group("Lobby", 1, 5, ServiceMode.DYNAMIC, null));
        services.add(service("Lobby", "Lobby-1", 0));
        ScalingManager manager = manager();

        List<ScalingDecision> decisions = manager.scaleTo("Lobby", 3);

        assertEquals(2, actions.starts.size());
        assertEquals(3, services.size());
        assertEquals(2, decisions.stream()
                .filter(decision -> decision.action() == ScalingDecision.Action.START).count());
        assertTrue(decisions.stream().allMatch(decision -> decision.rule().equals("manual")),
                decisions.toString());
    }

    @Test
    void manualScaleIgnoresTheCooldownOfTheAutomaticRun() {
        groups.add(group("Lobby", 1, 4, ServiceMode.DYNAMIC, null));
        actions.failStarts = true;
        ScalingManager manager = manager();

        manager.tick();
        assertEquals(1, actions.attempts);

        // Ein fehlgeschlagener Start legt den Backoff der automatischen Regel;
        // die manuelle Regel darf trotzdem sofort versuchen.
        actions.failStarts = false;
        manager.scaleTo("Lobby", 2);

        assertEquals(3, actions.attempts);
        assertEquals(2, services.size());
    }

    @Test
    void manualScaleStopsOnlyServicesWithoutPlayers() {
        groups.add(group("Lobby", 0, 4, ServiceMode.DYNAMIC, null));
        services.add(service("Lobby", "Lobby-1", 4));
        services.add(service("Lobby", "Lobby-2", 0));
        services.add(service("Lobby", "Lobby-3", 0));
        ScalingManager manager = manager();

        List<ScalingDecision> decisions = manager.scaleTo("Lobby", 1);

        assertEquals(2, actions.stops.size());
        assertTrue(actions.stops.contains("Lobby-2") && actions.stops.contains("Lobby-3"),
                actions.stops.toString());
        assertTrue(decisions.stream().anyMatch(decision -> decision.action() == ScalingDecision.Action.STOP),
                decisions.toString());
    }

    @Test
    void manualScaleReportsWhatItCannotStop() {
        groups.add(group("Lobby", 0, 4, ServiceMode.DYNAMIC, null));
        services.add(service("Lobby", "Lobby-1", 7));
        ScalingManager manager = manager();

        List<ScalingDecision> decisions = manager.scaleTo("Lobby", 0);

        assertTrue(actions.stops.isEmpty());
        assertTrue(decisions.stream().anyMatch(decision -> decision.action() == ScalingDecision.Action.NONE
                && decision.reason().contains("ohne Spieler stoppbar")), decisions.toString());
    }

    @Test
    void manualScaleKeepsStaticServices() {
        groups.add(group("Proxy", 1, 1, ServiceMode.STATIC, null));
        services.add(service("Proxy", "Proxy-1", 0));
        ScalingManager manager = manager();

        List<ScalingDecision> decisions = manager.scaleTo("Proxy", 0);

        assertTrue(actions.stops.isEmpty());
        assertTrue(decisions.stream().anyMatch(decision -> decision.reason().contains("statische")),
                decisions.toString());
    }

    @Test
    void manualScaleReportsAnAlreadyReachedTarget() {
        groups.add(group("Lobby", 1, 4, ServiceMode.DYNAMIC, null));
        services.add(service("Lobby", "Lobby-1", 0));
        ScalingManager manager = manager();

        List<ScalingDecision> decisions = manager.scaleTo("Lobby", 1);

        assertTrue(actions.starts.isEmpty());
        assertEquals(ScalingDecision.Action.NONE, decisions.getFirst().action());
        assertTrue(decisions.getFirst().reason().contains("bereits bei 1"), decisions.toString());
    }

    @Test
    void manualScaleStopsAtTheHardMaximum() {
        groups.add(group("Lobby", 1, 2, ServiceMode.DYNAMIC, null));
        ScalingManager manager = manager();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> manager.scaleTo("Lobby", 3));

        assertTrue(error.getMessage().contains("maxServices (2)"), error.getMessage());
    }

    @Test
    void manualScaleRejectsUnknownGroupsAndNegativeTargets() {
        groups.add(group("Lobby", 1, 2, ServiceMode.DYNAMIC, null));
        ScalingManager manager = manager();

        assertThrows(IllegalArgumentException.class, () -> manager.scaleTo("Factions", 1));
        assertThrows(IllegalArgumentException.class, () -> manager.scaleTo("Lobby", -1));
    }

    @Test
    void manualScaleCountsServicesThatAreStillStarting() {
        groups.add(group("Lobby", 1, 4, ServiceMode.DYNAMIC, null));
        services.add(service("Lobby", "Lobby-1", 0).toBuilder()
                .setState(ServiceState.STARTING)
                .build());
        ScalingManager manager = manager();

        manager.scaleTo("Lobby", 2);

        assertEquals(1, actions.starts.size());
    }

    private ScalingManager manager() {
        return new ScalingManager(() -> groups, () -> services, actions,
                new ScalingSettings(3), clock, Duration.ofSeconds(30), 1, host, degraded::contains);
    }

    private static ServiceGroup group(String name, int min, int max, ServiceMode mode, PlayerCountRule rule) {
        ServiceGroup.Builder builder = ServiceGroup.newBuilder()
                .setName(name)
                .setMode(mode)
                .setMinServices(min)
                .setMaxServices(max)
                .setMaxPlayers(100);
        if (rule != null) {
            builder.addScalingRules(ScalingRule.newBuilder()
                    .setId("player-count")
                    .setEnabled(true)
                    .setPlayerCount(rule));
        }
        return builder.build();
    }

    private static ServiceGroup withRule(ServiceGroup group, ScalingRule rule) {
        return group.toBuilder().addScalingRules(rule).build();
    }

    private static ScalingRule schedule(String cron, String timezone, int min, int max) {
        return ScalingRule.newBuilder()
                .setId("schedule")
                .setEnabled(true)
                .setSchedule(ScheduleRule.newBuilder()
                        .setCron(cron)
                        .setTimezone(timezone)
                        .setMinServices(min)
                        .setMaxServices(max))
                .build();
    }

    private static ScalingRule load(double maxCpu, double maxMemory, int minServices) {
        return ScalingRule.newBuilder()
                .setId("load")
                .setEnabled(true)
                .setLoad(LoadRule.newBuilder()
                        .setMaxHostCpu(maxCpu)
                        .setMaxHostMemory(maxMemory)
                        .setMinServices(minServices))
                .build();
    }

    private static ServiceInfo service(String group, String name, int players) {
        return ServiceInfo.newBuilder()
                .setId(ServiceId.random().toString())
                .setName(name)
                .setGroup(group)
                .setState(ServiceState.ONLINE)
                .setOnlinePlayers(players)
                .setMaxPlayers(100)
                .setStartedAt(System.nanoTime())
                .build();
    }

    private static final class FakeActions implements ScalingActions {

        private final List<ServiceInfo> services;
        private final List<String> starts = new ArrayList<>();
        private final List<String> stops = new ArrayList<>();
        private int startPlayers;
        private int next;
        private int attempts;
        private boolean failStarts;

        private FakeActions(List<ServiceInfo> services) {
            this.services = services;
        }

        @Override
        public ServiceInfo start(String group, Map<String, String> properties) throws ServiceLifecycleException {
            attempts++;
            if (failStarts) {
                throw new ServiceLifecycleException("Plattform-Jar fehlt");
            }
            String name = group + "-" + (++next);
            ServiceInfo info = ServiceInfo.newBuilder()
                    .setId(ServiceId.random().toString())
                    .setName(name)
                    .setGroup(group)
                    .setState(ServiceState.ONLINE)
                    .setOnlinePlayers(startPlayers)
                    .setMaxPlayers(100)
                    .setStartedAt(System.nanoTime())
                    .build();
            services.add(info);
            starts.add(name);
            return info;
        }

        @Override
        public ServiceInfo stop(ServiceId id, boolean force) {
            for (int i = 0; i < services.size(); i++) {
                ServiceInfo info = services.get(i);
                if (info.getId().equals(id.toString())) {
                    ServiceInfo stopped = info.toBuilder().setState(ServiceState.STOPPED).build();
                    services.set(i, stopped);
                    stops.add(info.getName());
                    return stopped;
                }
            }
            throw new IllegalArgumentException("Unbekannter Service: " + id);
        }
    }

    private static final class FakeHostMetrics implements HostMetrics {

        private double cpu = 0.1;
        private double memory = 0.1;

        @Override
        public double cpuLoad() {
            return cpu;
        }

        @Override
        public double memoryLoad() {
            return memory;
        }
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
}
