package dev.bytestorm.node.runtime;

import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.RedisSettings;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Alles, was eine {@link ServiceRuntime} zum Starten eines Services braucht
 * (Konzept §12.1).
 *
 * <p>Der Name ist der Service-Name nach Schema {@code <Gruppe>-<Nummer>}; er
 * dient als Log-Topic und für {@code logs/<name>/}. Die Platform-Jar wird vom
 * Platform-Adapter in den Service-Ordner gelegt (§10, §22.7), die Bridge-Jar
 * kommt aus der Distribution und wird nach {@code plugins/} kopiert. Die
 * Redis-Zugangsdaten werden ausschließlich als Umgebungsvariablen an den
 * Kindprozess gegeben, nie als Argument oder in Templates (§20).
 *
 * @param id          Service-ID
 * @param name        Service-Name
 * @param port        reservierter Service-Port (Container-Port und Host-Port)
 * @param directory   Arbeitsverzeichnis des Services
 * @param platformJar Server-/Proxy-Jar im Service-Ordner
 * @param bridgeJar   Bridge-Plugin-Jar oder {@code null}, wenn keine installiert wird
 * @param options     Speicher, JVM-/Plattform-Argumente, zusätzliche Umgebung
 * @param redis       Redis-Verbindung für die Bridge
 */
public record ServiceLaunch(
        ServiceId id,
        String name,
        PortLease port,
        Path directory,
        Path platformJar,
        Path bridgeJar,
        LaunchOptions options,
        RedisSettings redis) {

    /**
     * @param id          Service-ID
     * @param name        Service-Name
     * @param port        Port-Lease mit dem reservierten Port
     * @param directory   Arbeitsverzeichnis
     * @param platformJar Platform-Jar
     * @param bridgeJar   Bridge-Jar oder {@code null}
     * @param options     Startparameter
     * @param redis       Redis-Verbindung
     */
    public ServiceLaunch {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(redis, "redis");
        Objects.requireNonNull(port, "port");
        name = requireSimpleName(name);
        directory = requirePath(directory, "directory");
        platformJar = requirePath(platformJar, "platformJar");
        bridgeJar = bridgeJar == null ? null : requirePath(bridgeJar, "bridgeJar");
    }

    private static String requireSimpleName(String name) {
        Objects.requireNonNull(name, "name");
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.contains("/") || trimmed.contains("\\")
                || trimmed.equals(".") || trimmed.equals("..")) {
            throw new IllegalArgumentException("Ungültiger Service-Name: '" + name + "'");
        }
        return trimmed;
    }

    private static Path requirePath(Path path, String field) {
        Objects.requireNonNull(path, field);
        return path.toAbsolutePath().normalize();
    }
}
