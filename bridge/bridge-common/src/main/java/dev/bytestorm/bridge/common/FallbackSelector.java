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
                .filter(service -> service.getType() == ServiceType.SERVER)
                .filter(ServiceInfo::getFallback)
                .filter(service -> service.getState() == ServiceState.ONLINE)
                .filter(service -> service.getPort() > 0 && service.getPort() <= 65535)
                .min(Comparator.comparingInt(ServiceInfo::getFallbackPriority)
                        .thenComparing(ServiceInfo::getName));
    }
}
