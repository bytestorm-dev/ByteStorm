package dev.bytestorm.cli;

import dev.bytestorm.proto.GroupState;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardTest {

    private final boolean previous = Theme.enabled();

    @AfterEach
    void restoreColors() {
        Theme.setEnabled(previous);
    }

    @Test
    void rendersServicesGroupsAndHostLoad() {
        Theme.setEnabled(false);
        Dashboard.Snapshot snapshot = new Dashboard.Snapshot("1m 05s", 1, 2, 1, List.of(),
                128, 512, 0.25, 0.5,
                List.of(new Dashboard.ServiceRow("Lobby-1", "Lobby", ServiceState.ONLINE,
                                25565, 3, 10, "PROCESS", 2, 3),
                        // Proxies haben keine Obergrenze: dann steht dort nur die Zahl.
                        new Dashboard.ServiceRow("Proxy-1", "Proxy", ServiceState.ONLINE,
                                25577, 7, 0, "PROCESS", 3, 3)),
                List.of(new Dashboard.GroupRow("Lobby", ServiceType.SERVER, ServiceMode.DYNAMIC,
                        GroupState.HEALTHY, 1, 1, 5, false)),
                List.of(), List.of());

        String text = Dashboard.render(snapshot);

        assertTrue(text.contains("ByteStorm"), text);
        assertTrue(text.contains("Uptime 1m 05s"), text);
        assertTrue(text.contains("1/2 online"), text);
        assertTrue(text.contains("Host-CPU 25 %"), text);
        assertTrue(text.contains("Host-RAM 50 %"), text);
        assertTrue(text.contains("Lobby-1"), text);
        assertTrue(text.contains("3/10"), text);
        assertTrue(text.contains("Proxy-1"), text);
        assertFalse(text.contains("7/0"), "unbegrenzte Spielerzahl braucht keinen Nenner: " + text);
        assertTrue(text.contains("HEALTHY"), text);
        assertTrue(text.contains("1/5"), text);
        assertFalse(text.contains("Letzte Crashs"), "ohne Absturz keine Crash-Sektion: " + text);
        assertTrue(text.contains("Crash-Budget"), text);
        assertTrue(text.contains("2/3"), "Lobby-1 hat zwei Abstürze übrig: " + text);
        assertTrue(text.contains("3/3"), text);
    }

    @Test
    void namesTheReasonOfTheLastCrash() {
        Theme.setEnabled(false);
        Dashboard.Snapshot snapshot = new Dashboard.Snapshot("5s", 0, 1, 1, List.of("Lobby"),
                64, 256, null, null,
                List.of(new Dashboard.ServiceRow("Lobby-1", "Lobby", ServiceState.CRASHED,
                        30001, 0, 10, "PROCESS", 0, 3)),
                List.of(new Dashboard.GroupRow("Lobby", ServiceType.SERVER, ServiceMode.DYNAMIC,
                        GroupState.DEGRADED, 1, 1, 5, false)),
                List.of(new Dashboard.CrashRow("Lobby-1", "Start-Timeout", -1, "12:00:00",
                        "Lobby-1-20261101-120000-123")),
                List.of());

        String text = Dashboard.render(snapshot);

        assertTrue(text.contains("Letzte Crashs"), text);
        assertTrue(text.contains("0/3"), "das Budget ist aufgebraucht: " + text);
        assertTrue(text.contains("Lobby-1"), text);
        assertTrue(text.contains("Start-Timeout"), text);
        assertTrue(text.contains("Lobby-1-20261101-120000-123"), text);
    }

    @Test
    void reportsDegradedGroupsAndEmptyClouds() {
        Theme.setEnabled(false);
        Dashboard.Snapshot empty = new Dashboard.Snapshot("5s", 0, 0, 0, List.of("Lobby"),
                64, 256, null, null, List.of(), List.of(), List.of(), List.of());

        String text = Dashboard.render(empty);

        assertTrue(text.contains("Degradierte Gruppen: Lobby"), text);
        assertTrue(text.contains("Keine Services registriert."), text);
        assertTrue(text.contains("Keine Gruppen definiert."), text);
    }

    @Test
    void showsRecentLogLinesLikeTheConsole() {
        Theme.setEnabled(false);
        Dashboard.Snapshot snapshot = new Dashboard.Snapshot("5s", 0, 0, 0, List.of(),
                64, 256, null, null, List.of(), List.of(), List.of(),
                List.of("2026-11-02 12:00:03.123 [main] INFO  d.b.l.Bootstrap - ByteStorm bereit",
                        "2026-11-02 12:00:04.500 [main] WARN  d.b.c.s.ScalingManager - Minimum 1 nicht erreichbar"));

        String text = Dashboard.render(snapshot);

        assertTrue(text.contains("Letzte Logs"), text);
        assertTrue(text.contains("12:00:03|INFO  ByteStorm bereit"), text);
        assertTrue(text.contains("12:00:04|WARN  Minimum 1 nicht erreichbar"), text);
        assertFalse(text.contains("d.b.l.Bootstrap"), "der Logger-Name bleibt im Terminal weg: " + text);
    }

    @Test
    void intervalComesFromSeconds() {
        assertEquals(2_000, Dashboard.intervalMillis(null));
        assertEquals(5_000, Dashboard.intervalMillis("5"));
        assertThrows(IllegalArgumentException.class, () -> Dashboard.intervalMillis("0"));
        assertThrows(IllegalArgumentException.class, () -> Dashboard.intervalMillis("bald"));
    }
}
