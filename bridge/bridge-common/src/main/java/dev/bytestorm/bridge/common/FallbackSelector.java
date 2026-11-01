package dev.bytestorm.bridge.common;

import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceType;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Wählt das Fallback-Ziel für einen beitretenden Spieler (§14).
 *
 * <p>Berücksichtigt nur erreichbare Server-Gruppen mit Fallback-Flag. Kleinere
 * {@code fallback_priority} gewinnt; bei Gleichstand entscheidet der Name,
 * damit die Auswahl deterministisch bleibt.
 */
public final class FallbackSelector {

    private FallbackSelector() {
    }

    /**
     * @param services bekannte Services des Proxys
     * @return der bevorzugte Fallback-Server
     */
    public static Optional<ServiceInfo> select(Collection<ServiceInfo> services) {
        return select(services, null);
    }

    /**
     * @param services     bekannte Services des Proxys
     * @param excludedName Server, der nicht gewählt werden darf (z. B. der eben gekickte)
     * @return der bevorzugte Fallback-Server
     */
    public static Optional<ServiceInfo> select(Collection<ServiceInfo> services, String excludedName) {
        return services.stream()
                .filter(service -> excludedName == null || !service.getName().equals(excludedName))
                .filter(ServiceInfo::getFallback)
                .filter(FallbackSelector::joinable)
                .min(Comparator.comparingInt(ServiceInfo::getFallbackPriority)
                        .thenComparing(ServiceInfo::getName));
    }

    /**
     * Wählt den Zielserver für einen beitretenden Spieler.
     *
     * <p>Ein Fallback-Ziel hat Vorrang. Ist keine Gruppe als Fallback markiert,
     * nimmt die Auswahl den am wenigsten belasteten erreichbaren Server. Ohne
     * diesen Rückgriff könnte niemand beitreten, obwohl Server laufen: Velocity
     * löst die Auswahl über {@code try} auf, und der Platzhalter der Cloud ist zu
     * diesem Zeitpunkt bereits entfernt — die Verbindung endet dann mit
     * <i>"There are no available servers to connect you to"</i>.
     *
     * <p>Für die Umleitung nach einem Kick gilt das bewusst nicht: Dort kommen
     * nur Gruppen mit Fallback-Flag in Frage.
     *
     * @param services bekannte Services des Proxys
     * @return der bevorzugte Startserver
     */
    public static Optional<ServiceInfo> selectInitial(Collection<ServiceInfo> services) {
        Optional<ServiceInfo> fallback = select(services);
        if (fallback.isPresent()) {
            return fallback;
        }
        return services.stream()
                .filter(FallbackSelector::joinable)
                .min(Comparator.comparingInt(ServiceInfo::getOnlinePlayers)
                        .thenComparing(ServiceInfo::getName));
    }

    private static boolean joinable(ServiceInfo service) {
        return service.getType() == ServiceType.SERVER
                && service.getState() == ServiceState.ONLINE
                && service.getPort() > 0 && service.getPort() <= 65535;
    }
}
