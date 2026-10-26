package dev.bytestorm.core.config;

/**
 * Docker-Verbindung für die {@code DockerRuntime} (§12.3, §21).
 *
 * @param host Docker-Host, z. B. {@code unix:///var/run/docker.sock}
 */
public record DockerSettings(String host) {

    /** Default-Socket unter Linux. */
    public static final String DEFAULT_HOST = "unix:///var/run/docker.sock";

    /**
     * @param host Docker-Host, {@code null}/leer nutzt den Default
     */
    public DockerSettings {
        host = host == null || host.isBlank() ? DEFAULT_HOST : host.trim();
    }
}
