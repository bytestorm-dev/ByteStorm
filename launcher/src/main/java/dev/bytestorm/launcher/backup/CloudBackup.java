package dev.bytestorm.launcher.backup;

import com.mongodb.client.MongoDatabase;
import dev.bytestorm.cli.BackupCommandLine;
import dev.bytestorm.launcher.layout.CloudLayout;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Schreibt eine vollständige Sicherung der Cloud (§15).
 *
 * <p>Inhalt: alle MongoDB-Collections als JSON-Array je Collection, dazu die
 * Ordner {@code config/}, {@code templates/} und {@code static/} unter dem Präfix
 * {@code cloud/} und ein {@code manifest.json} mit Zählern. Die Ablage ist eine
 * Zip-Datei, weil Entpacken auf jedem Zielsystem ohne Zusatzwerkzeug geht; die
 * Wiederherstellung steht in {@code docs/backup.md}.
 *
 * <p>Nicht gesichert werden {@code running/} (transiente Arbeitsordner
 * dynamischer Services), Logs, Crash-Berichte und Redis: Der Live-Zustand wird
 * beim Start neu aufgebaut, MongoDB ist die Quelle der Wahrheit.
 *
 * <p>Die Datei enthält das Forwarding-Secret und die Datenbank-Zugänge; sie wird
 * deshalb mit {@code 0600} angelegt.
 */
@Slf4j
public final class CloudBackup implements BackupCommandLine.Runner {

    /** Zeitstempel im Dateinamen; sortierbar und ohne Sonderzeichen. */
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Ordner des Layouts, die in die Sicherung gehören. */
    static final List<String> DIRECTORIES = List.of("config", "templates", "static");

    static final String MONGO_PREFIX = "mongo/";

    static final String CLOUD_PREFIX = "cloud/";

    static final String MANIFEST = "manifest.json";

    /**
     * Liest die Collections. Als eigene Fassade, damit die Archivierung ohne
     * MongoDB prüfbar bleibt und ein Test keinen Server braucht.
     */
    @FunctionalInterface
    public interface MongoExporter {

        /**
         * @return je Collection die Dokumente als Extended-JSON
         */
        Map<String, List<String>> export();
    }

    private final CloudLayout layout;
    private final MongoExporter exporter;

    /**
     * @param layout   Cloud-Layout mit {@code config/}, {@code templates/} und {@code static/}
     * @param exporter liest die MongoDB-Collections
     */
    public CloudBackup(CloudLayout layout, MongoExporter exporter) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.exporter = Objects.requireNonNull(exporter, "exporter");
    }

    /**
     * Liest alle Collections einer Datenbank. System-Collections bleiben außen
     * vor; sie gehören der Datenbank, nicht der Cloud.
     *
     * @param database verbundene Datenbank
     * @return der Exporter
     */
    public static MongoExporter mongoExporter(MongoDatabase database) {
        Objects.requireNonNull(database, "database");
        return () -> {
            List<String> names = new ArrayList<>();
            for (String name : database.listCollectionNames()) {
                if (!name.startsWith("system.")) {
                    names.add(name);
                }
            }
            names.sort(String::compareTo);

            Map<String, List<String>> collections = new LinkedHashMap<>();
            for (String name : names) {
                List<String> documents = new ArrayList<>();
                for (Document document : database.getCollection(name).find()) {
                    documents.add(document.toJson());
                }
                collections.put(name, List.copyOf(documents));
            }
            return collections;
        };
    }

    /**
     * Schreibt die Sicherung.
     *
     * @param target Zieldatei oder {@code null} für {@code backups/bytestorm-<Zeitstempel>.zip}
     * @return Maße der angelegten Datei
     * @throws IOException wenn Schreiben oder Lesen fehlschlägt
     */
    @Override
    public BackupCommandLine.Archive run(Path target) throws IOException {
        Path archive = target == null ? defaultTarget() : target.toAbsolutePath().normalize();
        if (archive.getParent() != null) {
            Files.createDirectories(archive.getParent());
        }
        Map<String, List<String>> collections = exporter.export();

        int files = 0;
        long documents = 0;
        try (OutputStream out = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, List<String>> entry : collections.entrySet()) {
                documents += entry.getValue().size();
                writeEntry(zip, MONGO_PREFIX + entry.getKey() + ".json", jsonArray(entry.getValue()));
            }
            for (String directory : DIRECTORIES) {
                files += addTree(zip, layout.root().resolve(directory), directory);
            }
            writeEntry(zip, MANIFEST, manifest(collections, files));
        } catch (IOException | RuntimeException e) {
            // Eine halbe Sicherung ist schlimmer als keine: Sie sieht vollständig aus.
            Files.deleteIfExists(archive);
            throw e;
        }
        restrict(archive);
        log.info("Sicherung angelegt: {} ({} Collections, {} Dokumente, {} Dateien)",
                archive, collections.size(), documents, files);
        return new BackupCommandLine.Archive(archive, Files.size(archive),
                collections.size(), documents, files);
    }

    private Path defaultTarget() {
        return layout.backups().resolve("bytestorm-" + LocalDateTime.now().format(STAMP) + ".zip");
    }

    /**
     * @param documents Dokumente als Extended-JSON
     * @return ein JSON-Array, wie es {@code mongoimport --jsonArray} erwartet
     */
    private static String jsonArray(List<String> documents) {
        if (documents.isEmpty()) {
            return "[]\n";
        }
        return documents.stream().collect(Collectors.joining(",\n  ", "[\n  ", "\n]\n"));
    }

    private int addTree(ZipOutputStream zip, Path root, String prefix) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.filter(Files::isRegularFile).sorted().toList();
        }
        String base = root.toAbsolutePath().normalize().toString();
        int count = 0;
        for (Path path : paths) {
            String relative = path.toAbsolutePath().normalize().toString().substring(base.length())
                    .replace('\\', '/');
            if (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            zip.putNextEntry(new ZipEntry(CLOUD_PREFIX + prefix + "/" + relative));
            Files.copy(path, zip);
            zip.closeEntry();
            count++;
        }
        return count;
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String manifest(Map<String, List<String>> collections, int files) {
        StringBuilder text = new StringBuilder(256);
        text.append("{\n")
                .append("  \"createdAt\": \"").append(LocalDateTime.now().withNano(0)).append("\",\n")
                .append("  \"cloud\": \"").append(escape(layout.root().toString())).append("\",\n")
                .append("  \"directories\": [\"config\", \"templates\", \"static\"],\n")
                .append("  \"collections\": {");
        boolean first = true;
        for (Map.Entry<String, List<String>> entry : collections.entrySet()) {
            if (!first) {
                text.append(", ");
            }
            first = false;
            text.append('"').append(escape(entry.getKey())).append("\": ").append(entry.getValue().size());
        }
        text.append("},\n")
                .append("  \"files\": ").append(files).append(",\n")
                .append("  \"restore\": \"docs/backup.md\"\n")
                .append("}\n");
        return text.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void restrict(Path archive) {
        try {
            Files.setPosixFilePermissions(archive, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Auf Dateisystemen ohne POSIX-Rechte entfällt der Schutz; die Datei bleibt gültig.
            log.debug("Dateirechte für {} nicht setzbar: {}", archive, e.toString());
        }
    }
}
