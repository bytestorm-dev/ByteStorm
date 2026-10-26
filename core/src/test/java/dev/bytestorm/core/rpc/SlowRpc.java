package dev.bytestorm.core.rpc;

/**
 * Absichtlich langsame RPC für den Timeout-Test.
 */
public interface SlowRpc {

    /**
     * Blockiert länger als der Client-Timeout.
     */
    void slow();
}
