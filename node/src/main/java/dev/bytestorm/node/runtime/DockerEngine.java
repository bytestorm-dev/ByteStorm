package dev.bytestorm.node.runtime;

import java.io.Closeable;
import java.time.Duration;

/**
 * Schmale Fassade vor docker-java, damit {@link DockerRuntime} ohne Daemon
 * testbar bleibt. Die produktive Implementierung ist {@link DockerJavaEngine}.
 */
interface DockerEngine extends AutoCloseable {

    /**
     * Erstellt und startet einen Container.
     *
     * @param request Container-Konfiguration
     * @return Container-ID
     */
    String start(DockerContainerRequest request);

    /**
     * @param containerId Container-ID
     * @param timeout     Graceful-Timeout
     */
    void stop(String containerId, Duration timeout);

    /**
     * @param containerId Container-ID
     */
    void kill(String containerId);

    /**
     * @param containerId Container-ID
     * @return {@code true}, solange der Container läuft
     */
    boolean isRunning(String containerId);

    /**
     * Liest die Logs ab jetzt bis zum Container-Ende.
     *
     * @param containerId Container-ID
     * @param handler     Empfänger der Roh-Frames
     * @return Handle zum Abbrechen des Streams
     */
    Closeable streamLogs(String containerId, DockerLogHandler handler);

    @Override
    void close();
}
