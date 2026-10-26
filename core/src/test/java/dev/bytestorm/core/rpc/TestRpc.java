package dev.bytestorm.core.rpc;

/**
 * RPC-Interface für Integrationstests.
 */
public interface TestRpc {

    /**
     * @param value Eingabe
     * @return echo:value
     */
    String echo(String value);

    /**
     * Methode ohne Rückgabewert.
     */
    void noop();
}
