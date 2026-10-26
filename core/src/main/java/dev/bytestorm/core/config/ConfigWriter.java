package dev.bytestorm.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Schreibt {@code config/cloud.json} so, dass ein Abbruch keine halbe Datei
 * hinterlässt (§20, §21).
 *
 * <p>Geschrieben wird in eine temporäre Datei neben dem Ziel, die anschließend
 * atomar verschoben wird und {@code 0600} erhält — die Konfiguration enthält
 * Zugangsdaten. Dateisysteme ohne POSIX-Rechte (z. B. Windows) überspringen den
 * Rechteschritt.
 */
@UtilityClass
public final class ConfigWriter {

    /** Namen der temporären Datei; liegt im selben Verzeichnis wie das Ziel. */
    public static final String TEMP_SUFFIX = ".tmp";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    /**
     * Schreibt die Konfiguration atomar.
     *
     * @param file   Zieldatei, üblicherweise {@code config/cloud.json}
     * @param config zu speichernde Konfiguration
     * @throws IOException wenn das Verzeichnis fehlt, nicht schreibbar ist oder das Verschieben scheitert
     */
    public static void write(Path file, CloudConfig config) throws IOException {
        Path target = file.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
        try {
            MAPPER.writeValue(temp.toFile(), config);
            restrictToOwner(temp);
            move(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
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
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
