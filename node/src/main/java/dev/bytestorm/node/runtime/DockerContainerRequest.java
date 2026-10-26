package dev.bytestorm.node.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Beschreibung eines Service-Containers (Konzept §12.3).
 *
 * @param image            vorhandenes Image, kein eigener Build
 * @param command          Kommando im Container
 * @param workingDirectory Arbeitsverzeichnis im Container
 * @param environment      Umgebungsvariablen inkl. Bridge-Zugang
 * @param dataDirectory    Host-Ordner, der auf {@code /data} gebunden wird
 * @param port             Service-Port; Container- und Host-Port sind identisch
 */
record DockerContainerRequest(
        String image,
        List<String> command,
        String workingDirectory,
        Map<String, String> environment,
        Path dataDirectory,
        int port) {

    DockerContainerRequest {
        command = List.copyOf(command);
        environment = Map.copyOf(environment);
    }
}
