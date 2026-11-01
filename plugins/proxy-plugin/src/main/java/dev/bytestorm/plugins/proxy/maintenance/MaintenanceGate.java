package dev.bytestorm.plugins.proxy.maintenance;

import dev.bytestorm.plugins.proxy.config.ProxySettings;

import java.util.Objects;

/**
 * Entscheidet, ob ein Login während der Wartung abgewiesen wird (§16).
 *
 * <p>Die Regel ist absichtlich klein und ohne Proxy-Abhängigkeit: Permission und
 * Spieler kommen von außen, damit sie ohne Velocity testbar ist. Reihenfolge:
 * Wartung aus → alle dürfen; Bypass-Permission → darf; Ausnahmeliste (Namen ohne
 * Beachtung der Groß-/Kleinschreibung) → darf; sonst abweisen.
 */
public final class MaintenanceGate {

    private final ProxySettings.Maintenance settings;

    /**
     * @param settings Wartungseinstellungen
     */
    public MaintenanceGate(ProxySettings.Maintenance settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * @return die zugrunde liegenden Einstellungen
     */
    public ProxySettings.Maintenance settings() {
        return settings;
    }

    /**
     * @param playerName       Spielername aus dem Login
     * @param bypassPermission ob der Spieler die Bypass-Permission hat
     * @return {@code true}, wenn der Login abgelehnt wird
     */
    public boolean denies(String playerName, boolean bypassPermission) {
        if (!settings.enabled()) {
            return false;
        }
        if (bypassPermission) {
            return false;
        }
        if (playerName == null) {
            return true;
        }
        return settings.allowedPlayers().stream()
                .noneMatch(allowed -> allowed.equalsIgnoreCase(playerName));
    }
}
