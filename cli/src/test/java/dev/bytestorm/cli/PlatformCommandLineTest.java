package dev.bytestorm.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformCommandLineTest {

    @TempDir
    Path temp;

    private final List<String> installed = new ArrayList<>();

    private Path platforms;
    private PlatformCommandLine commandLine;

    @BeforeEach
    void setUp() throws IOException {
        platforms = Files.createDirectories(temp.resolve("platforms"));
        Files.writeString(platforms.resolve("paper-1.21.4.jar"), "paper");
        Files.writeString(platforms.resolve("velocity-4.3.0.jar"), "velocity");
        commandLine = new PlatformCommandLine(platforms, this::versions, this::install);
    }

    @Test
    void listShowsTheLocalJars() {
        String output = commandLine.execute(new String[]{"platform", "list"});

        assertTrue(output.contains("paper-1.21.4.jar"), output);
        assertTrue(output.contains("velocity-4.3.0.jar"), output);
        assertTrue(output.startsWith("Datei\tPlattform\tGröße\tGeändert"), output);
    }

    @Test
    void listOnAnEmptyDirectoryExplainsTheInstallCommand() throws IOException {
        PlatformCommandLine empty = new PlatformCommandLine(Files.createDirectories(temp.resolve("leer")),
                this::versions, this::install);

        String output = empty.execute(new String[]{"platform", "list"});

        assertTrue(output.contains("Keine Plattform-Jars"), output);
        assertTrue(output.contains("platform install"), output);
    }

    @Test
    void tasksShowWhatIsOutdated() {
        String output = commandLine.execute(new String[]{"platform", "tasks"});

        assertTrue(output.contains("paper\t1.21.4\t1.21.8\taktualisieren"), output);
        assertTrue(output.contains("velocity\t4.3.0\t4.3.0\taktuell"), output);
    }

    @Test
    void tasksCanBeLimitedToOnePlatform() {
        String output = commandLine.execute(new String[]{"platform", "tasks", "velocity"});

        assertTrue(output.contains("velocity"), output);
        assertFalse(output.contains("paper"), output);
    }

    @Test
    void versionsAreListedNewestFirst() {
        String output = commandLine.execute(new String[]{"platform", "versions", "paper"});

        assertTrue(output.contains("1.21.8"), output);
        int newest = output.indexOf("1.21.8");
        int older = output.indexOf("1.21.4");
        assertTrue(newest < older, output);
    }

    @Test
    void installUsesTheNewestStableVersionWithoutArgument() {
        String output = commandLine.execute(new String[]{"platform", "install", "paper"});

        assertEquals("Geladen: paper-1.21.8.jar (neueste stabile Version)", output);
        assertEquals(List.of("paper:1.21.8"), installed);
    }

    @Test
    void installPassesAnExplicitVersionThrough() {
        String output = commandLine.execute(new String[]{"platform", "install", "paper", "1.21.4"});

        assertTrue(output.startsWith("Geladen: paper-1.21.4.jar"), output);
        assertEquals(List.of("paper:1.21.4"), installed);
    }

    @Test
    void installReportsErrorsFromTheDownloader() {
        String output = commandLine.execute(new String[]{"platform", "install", "quark"});

        assertTrue(output.startsWith("Fehler: "), output);
        assertTrue(installed.isEmpty());
    }

    @Test
    void updateLoadsOnlyNewerVersions() {
        String output = commandLine.execute(new String[]{"platform", "update"});

        assertTrue(output.contains("paper: 1.21.8 geladen (paper-1.21.8.jar)"), output);
        assertTrue(output.contains("velocity: aktuell (4.3.0)"), output);
        assertEquals(List.of("paper:1.21.8"), installed);
    }

    @Test
    void platformNameWithoutSubcommandShowsInfo() {
        String output = commandLine.execute(new String[]{"platform", "paper"});

        assertTrue(output.contains("Plattform: paper"), output);
        assertTrue(output.contains("paper-1.21.4.jar"), output);
        assertTrue(output.contains("Verfügbar: 1.21.8"), output);
    }

    @Test
    void infoWithoutNameAndHelpAreReported() {
        assertTrue(commandLine.execute(new String[]{"platform", "info"}).contains("Plattform fehlt"));
        assertTrue(commandLine.execute(new String[]{"platform", "help"}).contains("platform update"));
    }

    private List<String> versions(String platform) {
        return switch (platform.toLowerCase()) {
            case "paper" -> List.of("1.21.8", "1.21.4");
            case "velocity" -> List.of("4.3.0");
            default -> throw new IllegalStateException("unbekannte Quelle: " + platform);
        };
    }

    private Path install(String platform, String version) {
        String resolved = version == null ? versions(platform).get(0) : version;
        installed.add(platform + ":" + resolved);
        return platforms.resolve(platform + "-" + resolved + ".jar");
    }
}
