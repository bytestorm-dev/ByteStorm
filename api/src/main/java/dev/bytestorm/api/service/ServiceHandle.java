package dev.bytestorm.api.service;

import java.nio.file.Path;

/**
 * Handle auf einen gestarteten Service, wie es eine {@code ServiceRuntime}
 * zurückgibt (Konzept §12.1). Die konkrete Bedeutung von {@link #nativeId()}
 * hängt von der Runtime ab (PID oder Container-ID).
 */
public interface ServiceHandle {

    /**
     * @return die Service-ID
     */
    ServiceId id();

    /**
     * @return PID des Prozesses bzw. ID des Containers
     */
    String nativeId();

    /**
     * @return Arbeitsverzeichnis des Services
     */
    Path directory();

    /**
     * @return {@code true}, wenn der Prozess/Container noch läuft
     */
    boolean isAlive();
}
