package dev.bytestorm.launcher.backup;

import dev.bytestorm.cli.BackupCommandLine;
import dev.bytestorm.launcher.layout.CloudLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudBackupTest {

    @TempDir
    Path temp;

    @Test
    void archivesMongoLayoutAndManifest() throws Exception {
        CloudLayout layout = layout();
        Files.writeString(layout.config().resolve("cloud.json"), "{\"runtime\":\"PROCESS\"}");
        Files.writeString(layout.templates().resolve("SERVER").resolve("server.properties"), "online-mode=false");
        Path world = Files.createDirectories(layout.staticServices().resolve("Lobby-1"));
        Files.writeString(world.resolve("eula.txt"), "eula=true");

        Map<String, List<String>> collections = new LinkedHashMap<>();
        collections.put("groups", List.of("{\"_id\": \"Lobby\"}", "{\"_id\": \"Proxy\"}"));
        CloudBackup backup = new CloudBackup(layout, () -> collections);
        Path archive = temp.resolve("sicherung.zip");

        BackupCommandLine.Archive result = backup.run(archive);

        assertEquals(1, result.collections());
        assertEquals(2, result.documents());
        assertEquals(3, result.files(), "cloud.json, server.properties und eula.txt");
        assertTrue(Files.isRegularFile(archive));

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertNotNull(zip.getEntry("mongo/groups.json"), "Collections liegen als JSON im Archiv");
            assertNotNull(zip.getEntry("cloud/config/cloud.json"));
            assertNotNull(zip.getEntry("cloud/templates/SERVER/server.properties"));
            assertNotNull(zip.getEntry("cloud/static/Lobby-1/eula.txt"));

            String mongo = read(zip, "mongo/groups.json");
            assertTrue(mongo.trim().startsWith("["), mongo);
            assertTrue(mongo.contains("\"_id\": \"Lobby\""), mongo);

            String manifest = read(zip, "manifest.json");
            assertTrue(manifest.contains("\"groups\": 2"), manifest);
            assertTrue(manifest.contains("\"files\": 3"), manifest);
            assertTrue(manifest.contains("docs/backup.md"), manifest);
        }
    }

    @Test
    void theDefaultTargetLandsInTheBackupsFolder() throws Exception {
        CloudLayout layout = layout();
        CloudBackup backup = new CloudBackup(layout, Map::of);

        BackupCommandLine.Archive result = backup.run(null);

        assertEquals(layout.backups(), result.file().getParent());
        assertTrue(result.file().getFileName().toString().startsWith("bytestorm-"));
        assertTrue(result.file().getFileName().toString().endsWith(".zip"));
        assertTrue(Files.isRegularFile(result.file()));
    }

    @Test
    void anEmptyCloudStillProducesAValidArchive() throws Exception {
        CloudLayout layout = layout();
        BackupCommandLine.Archive result = new CloudBackup(layout, Map::of).run(temp.resolve("leer.zip"));

        assertEquals(0, result.collections());
        assertEquals(0, result.documents());
        try (ZipFile zip = new ZipFile(result.file().toFile())) {
            assertNotNull(zip.getEntry("manifest.json"));
            assertTrue(read(zip, "manifest.json").contains("\"files\": 0"));
        }
    }

    private CloudLayout layout() throws Exception {
        CloudLayout layout = new CloudLayout(temp);
        layout.create();
        return layout;
    }

    private static String read(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        assertNotNull(entry, name);
        return new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
    }
}
