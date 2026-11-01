package dev.bytestorm.node.runtime;

import dev.bytestorm.core.config.RedisSettings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Properties;

/**
 * Übergibt der Bridge ihren Service und die Redis-Zugangsdaten (§12.2, §20).
 *
 * <p>Die Werte landen in einer Datei im Service-Ordner
 * ({@value #FILE_NAME}), Rechte {@code 0600}. In der Umgebung des Kindprozesses
 * steht nur der Dateiname — relativ, damit derselbe Wert für einen Prozess im
 * Service-Ordner und für einen Container mit {@code /data} als Arbeitsverzeichnis
 * passt.
 *
 * <p>Warum nicht mehr über die Umgebung: {@code /proc/<pid>/environ} und
 * {@code docker inspect} zeigen sie jedem, der auf dem Host oder am Docker-Socket
 * sitzt — und Kindprozesse des Servers erben sie. Eine Datei liegt im
 * Service-Ordner, den nur der Dienstbenutzer lesen darf.
 *
 * <p>Was bleibt: Wer als derselbe Benutzer auf die Datei zugreifen kann, kommt
 * weiterhin an das Passwort. Die Datei nimmt das Secret aus der Prozesstabelle,
 * ersetzt aber keine Rechte­trennung auf dem Host.
 */
public final class BridgeEnvironment {

    /** Umgebungsvariable mit dem Dateinamen der Bridge-Zugangsdaten. */
    public static final String BRIDGE_FILE = "BYTESTORM_BRIDGE_FILE";

    /** Datei im Service-Ordner; der Name ist relativ zum Arbeitsverzeichnis. */
    public static final String FILE_NAME = ".bytestorm-bridge.properties";

    private BridgeEnvironment() {
    }

    /**
     * Schreibt die Zugangsdaten in den Service-Ordner und liefert die Umgebung
     * für den Kindprozess.
     *
     * @param launch Service mit Redis-Verbindung und Zielordner
     * @return die Variablen für den Kindprozess; nur der Dateiname
     * @throws UncheckedIOException wenn die Datei nicht geschrieben werden kann
     */
    public static Map<String, String> of(ServiceLaunch launch) {
        RedisSettings redis = launch.redis();
        try {
            write(launch.directory(), launch.id().toString(), launch.name(),
                    redis.address(), redis.password());
        } catch (IOException e) {
            throw new UncheckedIOException("Bridge-Zugangsdaten für '" + launch.name()
                    + "' nicht schreibbar: " + launch.directory(), e);
        }
        return Map.of(BRIDGE_FILE, FILE_NAME);
    }

    static Path write(Path directory, String serviceId, String serviceName,
                      String redisAddress, String redisPassword) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(FILE_NAME);
        Path temp = target.resolveSibling(FILE_NAME + ".tmp");

        Properties properties = new Properties();
        properties.setProperty("service.id", serviceId);
        properties.setProperty("service.name", serviceName);
        properties.setProperty("redis.address", redisAddress);
        if (redisPassword != null) {
            properties.setProperty("redis.password", redisPassword);
        }
        try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            properties.store(out, "ByteStorm-Bridge: Zugangsdaten dieses Services. Nicht weitergeben.");
        }
        restrictToOwner(temp);
        move(temp, target);
        return target;
    }

    private static void restrictToOwner(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Dateisystem ohne POSIX-Rechte (z. B. Windows) — dort gelten ACLs.
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
