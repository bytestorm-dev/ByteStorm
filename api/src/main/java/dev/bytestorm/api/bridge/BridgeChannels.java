package dev.bytestorm.api.bridge;

import java.util.Objects;

/**
 * Redis-Kanäle zwischen Bridge-Plugins und Cloud (§15).
 *
 * <p>Die Kanäle gehören zum Wire-Protokoll und liegen deshalb in {@code api},
 * damit Cloud und Bridge dieselben Namen benutzen. Die Topic-Inhalte sind
 * Proto-Nachrichten aus {@code bridge.proto}; Logs und Zustandswechsel laufen
 * weiter über die allgemeinen {@code bs:event:...}-Topics.
 */
public final class BridgeChannels {

    /** Handshake-Anfrage einer Bridge ({@code BridgeHello}). */
    public static final String HELLO = "bs:bridge:hello";

    /** Heartbeats aller Bridges ({@code Heartbeat}). */
    public static final String HEARTBEAT = "bs:bridge:heartbeat";

    /** Live-Registry aller Services ({@code RMapCache} mit Heartbeat-TTL). */
    public static final String SERVICES = "bs:services";

    private static final String PREFIX = "bs:bridge:";
    private static final String ACK_SUFFIX = ":ack";
    private static final String COMMAND_SUFFIX = ":commands";
    private static final String EVENT_PREFIX = "bs:event:";

    private BridgeChannels() {
    }

    /**
     * @param serviceId Service-UUID als String
     * @return Topic, auf dem die Cloud den Handshake beantwortet
     */
    public static String ack(String serviceId) {
        return PREFIX + requireId(serviceId) + ACK_SUFFIX;
    }

    /**
     * @param serviceId Service-UUID als String
     * @return Topic für {@code ExecuteCommand} und {@code StopServiceRequest}
     */
    public static String commands(String serviceId) {
        return PREFIX + requireId(serviceId) + COMMAND_SUFFIX;
    }

    /**
     * @param fullName vollqualifizierter Proto-Name, z. B. {@code bytestorm.ServiceStateChanged}
     * @return Event-Topic {@code bs:event:<fullName>}
     */
    public static String event(String fullName) {
        Objects.requireNonNull(fullName, "fullName");
        if (fullName.isBlank()) {
            throw new IllegalArgumentException("fullName darf nicht leer sein");
        }
        return EVENT_PREFIX + fullName;
    }

    private static String requireId(String serviceId) {
        Objects.requireNonNull(serviceId, "serviceId");
        if (serviceId.isBlank() || serviceId.contains(":")) {
            throw new IllegalArgumentException("Ungültige Service-ID: '" + serviceId + "'");
        }
        return serviceId;
    }
}
