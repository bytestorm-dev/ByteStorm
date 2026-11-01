package dev.bytestorm.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EulaCommandLineTest {

    @TempDir
    Path cloud;

    @Test
    void statusOnAFreshCloudSaysNothingWasDecided() {
        String output = new EulaCommandLine(cloud).execute(new String[]{"eula", "status"});

        assertTrue(output.contains("nicht entschieden"), output);
        assertTrue(output.contains("eula accept"), "die Ausgabe nennt den Weg zur Zustimmung: " + output);
        assertFalse(Files.exists(cloud.resolve("templates").resolve("SERVER/eula.txt")));
    }

    @Test
    void acceptWritesTheTemplateAndExistingServiceFolders() throws Exception {
        Path lobby = Files.createDirectories(cloud.resolve("static/Lobby-1"));
        Files.writeString(lobby.resolve("eula.txt"), "eula=false\n");

        String output = new EulaCommandLine(cloud).execute(new String[]{"eula", "accept"});

        assertTrue(output.contains("Zustimmung gespeichert"), output);
        assertTrue(Files.readString(cloud.resolve("templates").resolve("SERVER/eula.txt"))
                .contains("eula=true"));
        assertTrue(Files.readString(lobby.resolve("eula.txt")).contains("eula=true"));
        assertTrue(output.contains("Service-Ordner angepasst: 1"), output);
        assertTrue(output.contains("service restart"), "die Wirkung auf laufende Services steht dabei: " + output);
    }

    @Test
    void revokeKeepsTheFileAndMarksTheRefusal() throws Exception {
        EulaCommandLine eula = new EulaCommandLine(cloud);
        eula.execute(new String[]{"eula", "accept"});

        String output = eula.execute(new String[]{"eula", "revoke"});

        Path file = cloud.resolve("templates").resolve("SERVER/eula.txt");
        assertTrue(Files.exists(file), "ein gelöschtes eula.txt würde reapply wieder auf 'true' setzen");
        assertTrue(Files.readString(file).contains("eula=false"));
        assertTrue(output.contains("zurückgenommen"), output);
        assertTrue(eula.execute(new String[]{"eula"}).contains("abgelehnt"),
                "ohne Argument zeigt 'eula' den Zustand");
    }

    @Test
    void anUnknownActionAndTheHelpAreHandled() {
        EulaCommandLine eula = new EulaCommandLine(cloud);

        assertTrue(eula.execute(new String[]{"eula", "quatsch"}).contains("Unbekannter EULA-Befehl"));
        String help = eula.execute(new String[]{"eula", "help"});
        assertTrue(help.contains("eula accept"), help);
        assertTrue(help.contains("eula revoke"), help);
    }
}
