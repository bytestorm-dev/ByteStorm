package dev.bytestorm.plugins.proxy.maintenance;

import dev.bytestorm.plugins.proxy.config.ProxySettings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceGateTest {

    @Test
    void allowsEveryoneWhileMaintenanceIsOff() {
        MaintenanceGate gate = gate(false, List.of());

        assertFalse(gate.denies("Notch", false));
    }

    @Test
    void deniesPlayersWithoutBypass() {
        MaintenanceGate gate = gate(true, List.of());

        assertTrue(gate.denies("Notch", false));
        assertFalse(gate.denies("Notch", true), "die Bypass-Permission öffnet die Tür");
    }

    @Test
    void allowsListedPlayersRegardlessOfCase() {
        MaintenanceGate gate = gate(true, List.of("Notch"));

        assertFalse(gate.denies("notch", false));
        assertTrue(gate.denies("jeb_", false));
    }

    @Test
    void deniesALoginWithoutAName() {
        MaintenanceGate gate = gate(true, List.of());

        assertTrue(gate.denies(null, false));
    }

    private static MaintenanceGate gate(boolean enabled, List<String> allowed) {
        return new MaintenanceGate(new ProxySettings.Maintenance(enabled,
                ProxySettings.Maintenance.DEFAULT_BYPASS_PERMISSION, allowed));
    }
}
