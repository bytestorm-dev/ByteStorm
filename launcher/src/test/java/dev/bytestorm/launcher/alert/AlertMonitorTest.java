package dev.bytestorm.launcher.alert;

import dev.bytestorm.core.alert.Alert;
import dev.bytestorm.core.alert.AlertSeverity;
import dev.bytestorm.core.health.ComponentHealth;
import dev.bytestorm.core.health.HealthReport;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertMonitorTest {

    private static final long GIB = 1024L * 1024 * 1024;

    private static final Predicate<String> NONE_DEGRADED = group -> false;

    @Test
    void reportsUnreachableDatastore() {
        List<Alert> alerts = AlertMonitor.signals(
                report(new ComponentHealth("redis", false, "Connection refused")),
                List.of(), List.of(), NONE_DEGRADED, -1, 0);

        assertEquals(1, alerts.size());
        Alert alert = alerts.get(0);
        assertEquals("health:redis", alert.source());
        assertEquals(AlertSeverity.CRITICAL, alert.severity());
        assertTrue(alert.message().contains("Redis ist nicht erreichbar"));
        assertTrue(alert.message().contains("Connection refused"));
    }

    @Test
    void namesKnownComponentsReadably() {
        List<Alert> alerts = AlertMonitor.signals(
                report(new ComponentHealth("mongo", false, "timeout"),
                        new ComponentHealth("runtime", false, "java fehlt")),
                List.of(), List.of(), NONE_DEGRADED, -1, 0);

        assertEquals(List.of("health:mongo", "health:runtime"),
                alerts.stream().map(Alert::source).toList());
        assertTrue(alerts.get(0).message().contains("MongoDB"));
        assertTrue(alerts.get(1).message().contains("Runtime"));
    }

    @Test
    void reportsDegradedGroupAndCrashedService() {
        ServiceGroup proxy = ServiceGroup.newBuilder().setName("Proxy").build();
        ServiceInfo crashed = ServiceInfo.newBuilder()
                .setName("Lobby-1")
                .setGroup("Lobby")
                .setState(ServiceState.CRASHED)
                .setPort(30000)
                .build();

        List<Alert> alerts = AlertMonitor.signals(report(), List.of(proxy), List.of(crashed),
                "Proxy"::equals, -1, 0);

        assertEquals(List.of("group:Proxy", "service:Lobby-1"),
                alerts.stream().map(Alert::source).toList());
        assertTrue(alerts.get(0).message().contains("DEGRADED"));
        assertTrue(alerts.get(0).message().contains("service start Proxy"),
                "der Alarm soll den nächsten Schritt nennen");
        assertEquals(AlertSeverity.WARNING, alerts.get(1).severity());
        assertTrue(alerts.get(1).message().contains("logs/Lobby-1/"));
    }

    @Test
    void reportsLowDiskInWholeGigabytes() {
        List<Alert> alerts = AlertMonitor.signals(report(), List.of(), List.of(), NONE_DEGRADED,
                2L * GIB + 300 * 1024 * 1024, 5L * GIB);

        assertEquals(1, alerts.size());
        assertEquals("disk", alerts.get(0).source());
        assertTrue(alerts.get(0).message().contains("Nur 2 GiB"));
        assertTrue(alerts.get(0).message().contains("Schwelle 5 GiB"));
    }

    @Test
    void staysSilentWhenEverythingIsFine() {
        List<Alert> alerts = AlertMonitor.signals(report(), List.of(), List.of(), NONE_DEGRADED,
                20L * GIB, 5L * GIB);
        assertTrue(alerts.isEmpty());
    }

    @Test
    void ignoresDiskWhenThresholdIsZeroOrUnknown() {
        assertTrue(AlertMonitor.signals(report(), List.of(), List.of(), NONE_DEGRADED, 1, 0).isEmpty());
        assertTrue(AlertMonitor.signals(report(), List.of(), List.of(), NONE_DEGRADED, -1, 5L * GIB)
                .isEmpty());
    }

    @Test
    void reportsHealthyGroupAsNothing() {
        ServiceGroup healthy = ServiceGroup.newBuilder().setName("Lobby").build();
        ServiceInfo online = ServiceInfo.newBuilder()
                .setName("Lobby-1")
                .setGroup("Lobby")
                .setState(ServiceState.ONLINE)
                .build();

        assertTrue(AlertMonitor.signals(report(), List.of(healthy), List.of(online), NONE_DEGRADED,
                20L * GIB, 5L * GIB).isEmpty());
    }

    private static HealthReport report(ComponentHealth... components) {
        Map<String, ComponentHealth> map = new LinkedHashMap<>();
        for (ComponentHealth component : new ArrayList<>(List.of(components))) {
            map.put(component.name(), component);
        }
        return new HealthReport(Instant.parse("2026-11-01T12:00:00Z"), Duration.ofMillis(5), map);
    }
}
