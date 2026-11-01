package dev.bytestorm.core.config;

import dev.bytestorm.api.service.PortRange;

/**
 * Port-Pools (§12.4, §21).
 *
 * @param proxy       Startport der Proxy-Services
 * @param serverStart Startport der Server-Services
 */
public record PortSettings(int proxy, int serverStart) {

    /** Default für Proxies. */
    public static final int DEFAULT_PROXY = 25565;

    /** Default für Server. */
    public static final int DEFAULT_SERVER_START = 30000;

    /**
     * @param proxy       Proxy-Port, {@code 0} nutzt den Default
     * @param serverStart Startport der Server, {@code 0} nutzt den Default
     */
    public PortSettings {
        proxy = proxy == 0 ? DEFAULT_PROXY : proxy;
        serverStart = serverStart == 0 ? DEFAULT_SERVER_START : serverStart;
    }

    /**
     * @return Port-Bereich für Server-Services (bis 65535)
     */
    public PortRange serverRange() {
        return new PortRange(serverStart, 65535);
    }

    /**
     * Port-Bereich für Proxy-Services: {@link #proxy()} bis vor den ersten
     * Server-Port, damit beide Pools getrennt bleiben.
     *
     * <p>Ein zweiter Proxy-Port ist kein Luxus: Der Proxy belegt seinen Port
     * exklusiv, und ein Zombie-Prozess oder eine hängende Reservierung hält
     * {@code 25565} auch dann, wenn der Service gestoppt ist. Mit dem Pool weicht
     * der Proxy nach oben aus, statt den Start mit "Kein freier Port" zu verweigern.
     * Der erste Versuch bleibt der konfigurierte Port.
     *
     * @return Port-Bereich ab {@link #proxy()}; liegt der Server-Pool darunter,
     *         bleibt es bei diesem einen Port
     */
    public PortRange proxyRange() {
        return new PortRange(proxy, Math.max(proxy, Math.min(serverStart - 1, 65535)));
    }
}
