package dev.bytestorm.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupCommandLineTest {

    @TempDir
    Path temp;

    private final List<Path> targets = new ArrayList<>();

    @Test
    void backupRunsTheArchiveAndReportsTheCounts() {
        BackupCommandLine cli = cli((target) -> new BackupCommandLine.Archive(
                temp.resolve("bytestorm-20261101-153000.zip"), 2_000_000L, 5, 412, 18311));

        String output = cli.execute(new String[]{"backup"});

        assertTrue(output.contains("Sicherung angelegt"), output);
        assertTrue(output.contains("1.9 MB"), output);
        assertTrue(output.contains("5 Collections"), output);
        assertTrue(output.contains("412 Dokumente"), output);
        assertTrue(output.contains("18311 Dateien"), output);
    }

    @Test
    void anExplicitOutputPathIsPassedThrough() {
        Path target = temp.resolve("woanders/sicherung.zip");
        BackupCommandLine cli = cli((path) -> new BackupCommandLine.Archive(target, 10L, 1, 1, 1));

        cli.execute(new String[]{"backup", "--out", target.toString()});

        assertEquals(List.of(target), targets);
    }

    @Test
    void aMissingPathIsRejectedWithoutRunning() {
        BackupCommandLine cli = cli((path) -> new BackupCommandLine.Archive(temp.resolve("x.zip"), 1L, 0, 0, 0));

        String output = cli.execute(new String[]{"backup", "--out"});

        assertTrue(output.contains("Pfad fehlt"), output);
        assertTrue(targets.isEmpty(), "ohne Pfad wird nichts geschrieben");
    }

    @Test
    void aFailingRunnerIsReportedWithoutStackTrace() {
        BackupCommandLine cli = new BackupCommandLine(target -> {
            throw new IOException("Platte voll");
        }, temp);

        String output = cli.execute(new String[]{"backup"});

        assertTrue(output.contains("Platte voll"), output);
    }

    @Test
    void listShowsTheExistingArchivesNewestFirst() throws Exception {
        Files.writeString(temp.resolve("bytestorm-20261031-120000.zip"), "alt");
        Files.writeString(temp.resolve("bytestorm-20261101-153000.zip"), "neu");
        Files.writeString(temp.resolve("notizen.txt"), "kein Archiv");
        BackupCommandLine cli = cli((path) -> new BackupCommandLine.Archive(temp, 1L, 0, 0, 0));

        String output = cli.execute(new String[]{"backup", "list"});

        assertTrue(output.contains("Sicherungen in " + temp), output);
        assertTrue(output.contains("bytestorm-20261101-153000.zip"), output);
        assertTrue(output.contains("bytestorm-20261031-120000.zip"), output);
        assertTrue(!output.contains("notizen.txt"), output);
        assertTrue(output.indexOf("bytestorm-20261101-153000.zip")
                < output.indexOf("bytestorm-20261031-120000.zip"), "die neueste Sicherung steht oben");
    }

    @Test
    void helpPrintsTheUsage() {
        BackupCommandLine cli = cli((path) -> new BackupCommandLine.Archive(temp, 1L, 0, 0, 0));

        String output = cli.execute(new String[]{"backup", "help"});

        assertTrue(output.contains("backup --out"), output);
    }

    private BackupCommandLine cli(BackupCommandLine.Runner runner) {
        return new BackupCommandLine(target -> {
            targets.add(target);
            return runner.run(target);
        }, temp);
    }
}
