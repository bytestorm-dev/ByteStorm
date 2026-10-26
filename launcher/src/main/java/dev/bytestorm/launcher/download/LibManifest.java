package dev.bytestorm.launcher.download;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Manifest der Dateien in {@code lib/} — Grundlage für spätere Updates (§5.1).
 *
 * <p>Die Datei {@code lib/.manifest.json} wird atomar geschrieben, damit ein
 * Abbruch nie ein halbes Manifest hinterlässt.
 *
 * @param dependencies Einträge je heruntergeladener Jar
 */
public record LibManifest(List<Entry> dependencies) {

    /** Name des Manifests. */
    public static final String FILE_NAME = ".manifest.json";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            // Ein neueres Manifest darf von einer älteren Cloud gelesen werden.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * @param dependencies Einträge, {@code null} wird zu einer leeren Liste
     */
    public LibManifest {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    /**
     * Liest das Manifest.
     *
     * @param libDirectory {@code lib/}-Verzeichnis
     * @return das Manifest; ein leeres, wenn die Datei fehlt
     * @throws IOException wenn die Datei nicht lesbar oder kein gültiges JSON ist
     */
    public static LibManifest read(Path libDirectory) throws IOException {
        Path file = libDirectory.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return new LibManifest(List.of());
        }
        return MAPPER.readValue(file.toFile(), LibManifest.class);
    }

    /**
     * Schreibt das Manifest atomar.
     *
     * @param libDirectory {@code lib/}-Verzeichnis
     * @throws IOException wenn die Datei nicht geschrieben werden kann
     */
    public void write(Path libDirectory) throws IOException {
        Files.createDirectories(libDirectory);
        Path target = libDirectory.resolve(FILE_NAME);
        Path temp = libDirectory.resolve(FILE_NAME + ".tmp");
        try {
            MAPPER.writeValue(temp.toFile(), this);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * @param fileName Dateiname im {@code lib/}-Verzeichnis
     * @return Eintrag, falls vorhanden
     */
    public Optional<Entry> find(String fileName) {
        return dependencies.stream().filter(entry -> entry.file().equals(fileName)).findFirst();
    }

    /**
     * Ein Eintrag im Manifest.
     *
     * @param file       Dateiname, z. B. {@code en2do-3.3.3.jar}
     * @param groupId    Maven-Gruppe
     * @param artifactId Maven-Artefakt
     * @param version    Version
     * @param sha256     Prüfsumme der Datei
     * @param bytes      Dateigröße
     */
    public record Entry(String file, String groupId, String artifactId, String version, String sha256, long bytes) {

        /**
         * @param file       Dateiname
         * @param groupId    Maven-Gruppe
         * @param artifactId Maven-Artefakt
         * @param version    Version
         * @param sha256     Prüfsumme
         * @param bytes      Dateigröße
         */
        public Entry {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(sha256, "sha256");
            if (bytes < 0) {
                throw new IllegalArgumentException("bytes darf nicht negativ sein: " + bytes);
            }
            sha256 = sha256.toLowerCase(Locale.ROOT);
        }

        /**
         * @return {@code groupId:artifactId:version}
         */
        public String coordinates() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }
}
