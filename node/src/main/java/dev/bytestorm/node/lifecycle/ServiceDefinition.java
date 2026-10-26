package dev.bytestorm.node.lifecycle;

import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.proto.ServiceGroup;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Definition eines Services vor dem Start (§9.1, §12).
 *
 * @param id          Service-ID
 * @param name        Service-Name nach Schema {@code <Gruppe>-<Nummer>}
 * @param group       Gruppe mit Template-Liste, Speicher und Runtime-Override
 * @param directory   Arbeitsverzeichnis
 * @param platformJar Platform-Jar
 * @param bridgeJar   Bridge-Jar oder {@code null}
 * @param options     Startparameter
 * @param redis       Redis-Verbindung der Bridge
 */
public record ServiceDefinition(
        ServiceId id,
        String name,
        ServiceGroup group,
        Path directory,
        Path platformJar,
        Path bridgeJar,
        LaunchOptions options,
        RedisSettings redis) {

    /**
     * @param id          Service-ID
     * @param name        Service-Name
     * @param group       Gruppe
     * @param directory   Arbeitsverzeichnis
     * @param platformJar Platform-Jar
     * @param bridgeJar   Bridge-Jar oder {@code null}
     * @param options     Startparameter
     * @param redis       Redis-Verbindung
     */
    public ServiceDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(redis, "redis");
        name = requireName(name);
        directory = requirePath(directory, "directory");
        platformJar = requirePath(platformJar, "platformJar");
        bridgeJar = bridgeJar == null ? null : requirePath(bridgeJar, "bridgeJar");
    }

    /**
     * @return {@code true}, wenn der Service nach dem Stop verworfen wird
     */
    public boolean dynamic() {
        return group.getMode() == dev.bytestorm.proto.ServiceMode.DYNAMIC;
    }

    private static String requireName(String name) {
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
