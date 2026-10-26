package dev.bytestorm.launcher;

/**
 * RPC-Interface für den Bootstrap-Integrationstest.
 */
public interface PingRpc {

    /**
     * @return {@code pong}
     */
    String ping();
}
