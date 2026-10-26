package dev.bytestorm.node.lifecycle;

import dev.bytestorm.proto.ServiceInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Unerwarteter Exit eines Services, bevor der Crash-Handler ihn verarbeitet.
 *
 * @param info      Service zum Crash-Zeitpunkt
 * @param directory Service-Ordner für den Bericht
 * @param exitCode  Exit-Code, bei Containern oder Timeout ggf. {@code -1}
 * @param reason    Ursache, z. B. {@code unerwarteter Exit} oder {@code Start-Timeout}
 * @param logTail   letzte Logzeilen
 */
public record ServiceExit(ServiceInfo info, Path directory, int exitCode, String reason, List<String> logTail) {

    /**
     * @param info      Service
     * @param directory Service-Ordner
     * @param exitCode  Exit-Code
     * @param reason    Ursache
     * @param logTail   Logzeilen
     */
    public ServiceExit {
        Objects.requireNonNull(reason, "reason");
        logTail = List.copyOf(logTail);
    }
}
