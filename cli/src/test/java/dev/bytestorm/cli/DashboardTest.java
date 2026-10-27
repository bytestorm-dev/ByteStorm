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
                                25565, 3, 10, "PROCESS"),
                        // Proxies haben keine Obergrenze: dann steht dort nur die Zahl.
                        new Dashboard.ServiceRow("Proxy-1", "Proxy", ServiceState.ONLINE,
                                25577, 7, 0, "PROCESS")),
                List.of(new Dashboard.GroupRow("Lobby", ServiceType.SERVER, ServiceMode.DYNAMIC,
                        GroupState.HEALTHY, 1, 1, 5, false)));

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
    }

    @Test
    void reportsDegradedGroupsAndEmptyClouds() {
        Theme.setEnabled(false);
        Dashboard.Snapshot empty = new Dashboard.Snapshot("5s", 0, 0, 0, List.of("Lobby"),
                64, 256, null, null, List.of(), List.of());

        String text = Dashboard.render(empty);

        assertTrue(text.contains("Degradierte Gruppen: Lobby"), text);
        assertTrue(text.contains("Keine Services registriert."), text);
        assertTrue(text.contains("Keine Gruppen definiert."), text);
    }

    @Test
    void intervalComesFromSeconds() {
        assertEquals(2_000, Dashboard.intervalMillis(null));
        assertEquals(5_000, Dashboard.intervalMillis("5"));
        assertThrows(IllegalArgumentException.class, () -> Dashboard.intervalMillis("0"));
        assertThrows(IllegalArgumentException.class, () -> Dashboard.intervalMillis("bald"));
    }
}
