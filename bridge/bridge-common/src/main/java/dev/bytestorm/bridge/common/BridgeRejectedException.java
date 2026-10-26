package dev.bytestorm.bridge.common;

/**
 * Die Cloud hat den Handshake abgelehnt, z. B. wegen einer inkompatiblen
 * Proto-Major-Version oder einer unbekannten Service-ID (§6.4).
 */
public class BridgeRejectedException extends BridgeException {

    /**
     * @param message Grund aus {@code BridgeHelloAck.reason}
     */
    public BridgeRejectedException(String message) {
        super(message);
    }
}
