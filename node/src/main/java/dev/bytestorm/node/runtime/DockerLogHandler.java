package dev.bytestorm.node.runtime;

/**
 * Empfängt Roh-Frames des Docker-Logstreams; die Zeilenbildung übernimmt
 * {@link DockerRuntime}.
 */
interface DockerLogHandler {

    /**
     * @param stderr  {@code true} für stderr
     * @param payload Roh-Bytes, ggf. ohne vollständige Zeile
     */
    void frame(boolean stderr, byte[] payload);

    /**
     * Der Stream ist beendet oder abgebrochen.
     */
    void end();
}
