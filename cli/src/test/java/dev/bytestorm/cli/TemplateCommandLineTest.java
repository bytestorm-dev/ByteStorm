package dev.bytestorm.cli;

import dev.bytestorm.controller.template.TemplateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateCommandLineTest {

    /** Entfernt Tabellenabstände: Zellen bleiben durch genau ein Leerzeichen getrennt. */
    private static String plain(String text) {
        return String.join("\n", text.lines()
                .map(line -> line.strip().replaceAll("\\s+", " "))
                .toList());
    }

    @TempDir
    Path temp;

    private Path templates;
    private Path service;
    private TemplateCommandLine cli;

    @BeforeEach
    void setUp() throws IOException {
        templates = Files.createDirectories(temp.resolve("templates"));
        service = Files.createDirectories(temp.resolve("running/Lobby-1-9f2c"));
        cli = new TemplateCommandLine(new TemplateManager(templates, name -> name.equals("Lobby-1")
                ? Optional.of(service)
                : Optional.empty()));
    }

    @Test
    void showsTheUsageWithoutACommand() {
        String output = cli.execute(new String[] {"templates"});

        assertTrue(output.contains("templates list"), output);
        assertTrue(output.contains("templates sync <service> <template>"), output);
    }

    @Test
    void listsTemplatesWithSizeAndLayer() throws IOException {
        write(templates.resolve("ALL/eula.txt"), "eula=true");
        write(templates.resolve("Lobby/server.properties"), "motd=Hallo");

        String output = cli.execute(new String[] {"templates", "list"});

        String[] lines = plain(output).split("\n");
        assertEquals("Name Dateien Größe Geändert Ebene", lines[0]);
        // Zeile 1 ist die Trennlinie der Tabelle.
        assertTrue(lines[2].startsWith("ALL 1 9 B"), lines[2]);
        assertTrue(lines[2].endsWith("fest"), lines[2]);
        assertTrue(lines[3].startsWith("Lobby 1 "), lines[3]);
        assertTrue(lines[3].endsWith("eigenes"), lines[3]);
    }

    @Test
    void createsDeletesAndCopiesTemplates() throws IOException {
        write(templates.resolve("Lobby/server.properties"), "motd=Hallo");

        assertTrue(cli.execute(new String[] {"templates", "create", "Lobby-Test"}).contains("angelegt"));
        assertTrue(Files.isDirectory(templates.resolve("Lobby-Test")));

        String copied = cli.execute(new String[] {"templates", "copy", "Lobby", "Lobby-Kopie"});
        assertTrue(copied.contains("1 Dateien"), copied);
        assertEquals("motd=Hallo", Files.readString(templates.resolve("Lobby-Kopie/server.properties")));

        String deleted = cli.execute(new String[] {"templates", "delete", "Lobby-Kopie"});
        assertTrue(deleted.contains("gelöscht"), deleted);
        assertFalse(Files.exists(templates.resolve("Lobby-Kopie")));
    }

    @Test
    void deleteHintsAtTheFixedLayers() throws IOException {
        Files.createDirectories(templates.resolve("SERVER"));

        String output = cli.execute(new String[] {"templates", "delete", "SERVER"});

        assertTrue(output.contains("Feste Ebene"), output);
    }

    @Test
    void syncsAServiceDirectoryIntoATemplate() throws IOException {
        write(service.resolve("server.properties"), "server-port=30001");
        write(service.resolve("logs/latest.log"), "log");
        Files.createDirectories(templates.resolve("SERVER"));

        String output = cli.execute(new String[] {"templates", "sync", "Lobby-1", "SERVER"});

        assertTrue(output.contains("1 Dateien"), output);
        assertTrue(output.contains("service reapply"), output);
        assertTrue(Files.isRegularFile(templates.resolve("SERVER/server.properties")));
        assertFalse(Files.exists(templates.resolve("SERVER/logs")));
    }

    @Test
    void reportsErrorsInPlainText() throws IOException {
        assertTrue(cli.execute(new String[] {"templates", "create", "Lobby Test"})
                .startsWith("Fehler: Templatename"));
        assertTrue(cli.execute(new String[] {"templates", "delete", "Unbekannt"})
                .startsWith("Fehler: Unbekanntes Template"));
        Files.createDirectories(templates.resolve("SERVER"));
        assertTrue(cli.execute(new String[] {"templates", "sync", "Lobby-1", "Unbekannt"})
                .startsWith("Fehler: Unbekanntes Template"));
        assertTrue(cli.execute(new String[] {"templates", "sync", "Factions-1", "SERVER"})
                .contains("Kein Service-Ordner"));
        assertTrue(cli.execute(new String[] {"templates", "copy", "Lobby"})
                .startsWith("Quelle und Ziel fehlen"));
        assertTrue(cli.execute(new String[] {"templates", "sync", "Lobby-1"})
                .startsWith("Service und Template fehlen"));
        assertTrue(cli.execute(new String[] {"templates", "upgrade"})
                .startsWith("Unbekanntes Template-Kommando: upgrade"));
    }

    @Test
    void offersTheTemplateNamesForCompletion() throws IOException {
        Files.createDirectories(templates.resolve("ALL"));
        Files.createDirectories(templates.resolve("Lobby"));

        assertEquals(java.util.List.of("ALL", "Lobby"), cli.names());
    }

    @Test
    void formatsSizes() {
        assertEquals("0 B", TemplateCommandLine.size(0));
        assertEquals("1023 B", TemplateCommandLine.size(1023));
        assertEquals("1,0 KiB", TemplateCommandLine.size(1024));
        assertEquals("1,5 MiB", TemplateCommandLine.size(1024 * 1536));
        assertEquals("2,0 GiB", TemplateCommandLine.size(2L * 1024 * 1024 * 1024));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
