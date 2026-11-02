package dev.bytestorm.controller.template;

import dev.bytestorm.controller.template.TemplateManager.SyncResult;
import dev.bytestorm.controller.template.TemplateManager.TemplateEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateManagerTest {

    @TempDir
    Path temp;

    private Path templates;
    private Path service;

    @BeforeEach
    void setUp() throws IOException {
        templates = Files.createDirectories(temp.resolve("cloud/templates"));
        service = Files.createDirectories(temp.resolve("cloud/running/Lobby-1-6f1a"));
    }

    @Test
    void listsFolderContentsAndMarksTheFixedLayers() throws Exception {
        write(templates.resolve("ALL/zusatz.txt"), "hallo");
        write(templates.resolve("ALL/config/tief.txt"), "welt");
        write(templates.resolve("Lobby/server.properties"), "motd=Hallo");

        List<TemplateEntry> entries = manager().list();

        assertEquals(List.of("ALL", "Lobby"), entries.stream().map(TemplateEntry::name).toList());
        TemplateEntry all = entries.getFirst();
        assertTrue(all.fixed(), "ALL ist eine feste Ebene");
        assertEquals(2, all.files());
        assertEquals(9, all.bytes(), "5 + 4 Zeichen");
        assertFalse(entries.getLast().fixed(), "Lobby ist ein eigenes Template");
        assertEquals(1, entries.getLast().files());
    }

    @Test
    void createsAndDeletesTemplates() {
        TemplateManager manager = manager();

        TemplateEntry created = manager.create("Lobby-Test");
        assertEquals("Lobby-Test", created.name());
        assertTrue(Files.isDirectory(templates.resolve("Lobby-Test")));
        assertThrows(IllegalArgumentException.class, () -> manager.create("Lobby-Test"));

        assertFalse(manager.delete("Lobby-Test"), "eigene Templates sind keine feste Ebene");
        assertFalse(Files.exists(templates.resolve("Lobby-Test")));
        assertThrows(IllegalArgumentException.class, () -> manager.delete("Lobby-Test"));
    }

    @Test
    void reportsDeletedFixedLayers() throws Exception {
        Files.createDirectories(templates.resolve("SERVER"));

        assertTrue(manager().delete("SERVER"), "der Aufrufer soll die feste Ebene erkennen können");
        assertTrue(manager().create("SERVER").fixed());
    }

    @Test
    void rejectsNamesThatAreNoTemplateNames() {
        TemplateManager manager = manager();

        assertThrows(IllegalArgumentException.class, () -> manager.create("Lobby Test"));
        assertThrows(IllegalArgumentException.class, () -> manager.create("../Lobby"));
        assertThrows(IllegalArgumentException.class, () -> manager.create("a".repeat(33)));
        assertThrows(IllegalArgumentException.class, () -> manager.create("Lobby/Tief"));
    }

    @Test
    void copiesATemplateRecursively() throws Exception {
        write(templates.resolve("Lobby/server.properties"), "motd=Hallo");
        write(templates.resolve("Lobby/config/world/paper.yml"), "welt: true");
        TemplateManager manager = manager();

        TemplateEntry copy = manager.copy("Lobby", "Lobby-Test");

        assertEquals(2, copy.files());
        assertEquals("motd=Hallo", Files.readString(templates.resolve("Lobby-Test/server.properties")));
        assertEquals("welt: true",
                Files.readString(templates.resolve("Lobby-Test/config/world/paper.yml")));
        assertTrue(Files.isRegularFile(templates.resolve("Lobby/server.properties")),
                "die Quelle bleibt unangetastet");
        assertThrows(IllegalArgumentException.class, () -> manager.copy("Unbekannt", "Ziel"));
        assertThrows(IllegalArgumentException.class, () -> manager.copy("Lobby", "Lobby-Test"));
    }

    @Test
    void syncsTheServiceDirectoryButLeavesRuntimeDataBehind() throws Exception {
        write(service.resolve("server.properties"), "server-port=30001");
        write(service.resolve("config/paper-global.yml"), "secret: geheim");
        write(service.resolve("velocity.toml"), "bind = \"0.0.0.0:25565\"");
        write(service.resolve("plugins/MyPlugin.jar"), "eigenes plugin");
        write(service.resolve("plugins/bridge-paper-1.0-SNAPSHOT.jar"), "bridge");
        write(service.resolve("server.jar"), "plattform");
        write(service.resolve("logs/latest.log"), "log");
        write(service.resolve("crash-reports/crash.txt"), "crash");
        write(service.resolve("world/level.dat"), "welt");
        Files.createDirectories(templates.resolve("SERVER"));

        SyncResult result = manager().sync("Lobby-1", "SERVER");

        assertEquals(4, result.files(), "Konfiguration und eigenes Plugin, ohne Laufzeitdaten");
        assertEquals("SERVER", result.template().name());
        assertTrue(Files.isRegularFile(templates.resolve("SERVER/server.properties")));
        assertTrue(Files.isRegularFile(templates.resolve("SERVER/config/paper-global.yml")));
        assertTrue(Files.isRegularFile(templates.resolve("SERVER/velocity.toml")));
        assertTrue(Files.isRegularFile(templates.resolve("SERVER/plugins/MyPlugin.jar")));
        assertFalse(Files.exists(templates.resolve("SERVER/logs")), "logs/ gehört nicht ins Template");
        assertFalse(Files.exists(templates.resolve("SERVER/crash-reports")));
        assertFalse(Files.exists(templates.resolve("SERVER/world")));
        assertFalse(Files.exists(templates.resolve("SERVER/server.jar")));
        assertFalse(Files.exists(templates.resolve("SERVER/plugins/bridge-paper-1.0-SNAPSHOT.jar")),
                "die Bridge installiert der Node selbst");
    }

    @Test
    void syncsOnlyASubfolderOfTheServiceIntoTheTemplate() throws Exception {
        write(service.resolve("server.properties"), "server-port=30001");
        write(service.resolve("plugins/MyPlugin/config.yml"), "farbe: aqua");
        write(service.resolve("plugins/MyPlugin/logs/alt.log"), "log");
        Files.createDirectories(templates.resolve("SERVER"));

        SyncResult result = manager().sync("Lobby-1", "SERVER", "plugins/MyPlugin");

        assertEquals(1, result.files(), "nur der Plugin-Ordner, ohne Laufzeitdaten");
        assertEquals("farbe: aqua",
                Files.readString(templates.resolve("SERVER/plugins/MyPlugin/config.yml")));
        assertFalse(Files.exists(templates.resolve("SERVER/plugins/MyPlugin/logs")));
        assertFalse(Files.exists(templates.resolve("SERVER/server.properties")),
                "der Rest des Service-Ordners bleibt draußen");
    }

    @Test
    void syncsASingleFileEvenIfItWouldNormallyBeSkipped() throws Exception {
        write(service.resolve("eula.txt"), "eula=true");
        Files.createDirectories(templates.resolve("SERVER"));

        SyncResult result = manager().sync("Lobby-1", "SERVER", "eula.txt");

        assertEquals(1, result.files());
        assertEquals("eula=true", Files.readString(templates.resolve("SERVER/eula.txt")));
    }

    @Test
    void syncRejectsPathsOutsideTheServiceDirectory() throws Exception {
        Files.createDirectories(templates.resolve("SERVER"));
        TemplateManager manager = manager();

        assertThrows(IllegalArgumentException.class,
                () -> manager.sync("Lobby-1", "SERVER", "../anderer-service"));
        assertThrows(IllegalArgumentException.class,
                () -> manager.sync("Lobby-1", "SERVER", "/etc/passwd"));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> manager.sync("Lobby-1", "SERVER", "plugins/Fehlt"));
        assertTrue(missing.getMessage().contains("plugins/Fehlt"), missing.getMessage());
    }

    @Test
    void syncNeedsAKnownServiceAndTemplate() throws Exception {
        Files.createDirectories(templates.resolve("SERVER"));
        TemplateManager manager = manager();

        IllegalArgumentException unknownService = assertThrows(IllegalArgumentException.class,
                () -> manager.sync("Factions-1", "SERVER"));
        assertTrue(unknownService.getMessage().contains("Factions-1"), unknownService.getMessage());
        assertThrows(IllegalArgumentException.class, () -> manager.sync("Lobby-1", "Unbekannt"));
    }

    private TemplateManager manager() {
        return new TemplateManager(templates, name -> name.equals("Lobby-1")
                ? Optional.of(service)
                : Optional.empty());
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
