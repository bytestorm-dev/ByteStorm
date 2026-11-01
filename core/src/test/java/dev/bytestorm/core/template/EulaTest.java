package dev.bytestorm.core.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EulaTest {

    @TempDir
    Path cloud;

    @Test
    void nothingIsDecidedWithoutExplicitConsent() {
        assertEquals(Eula.State.MISSING, Eula.status(cloud));
        assertFalse(Files.exists(Eula.file(cloud)));
    }

    @Test
    void acceptWritesTheTemplateAndExistingServiceFolders() throws Exception {
        Path lobby = service("static/Lobby-1");
        Path running = service("running/Proxy-1");
        Path withoutEula = Files.createDirectories(cloud.resolve("static/Fresh-1"));

        List<Path> written = Eula.accept(cloud);

        assertEquals(Eula.State.ACCEPTED, Eula.status(cloud));
        assertEquals(List.of(Eula.file(cloud),
                        lobby.resolve("eula.txt"),
                        running.resolve("eula.txt")),
                written, "Service-Ordner ohne eula.txt bleiben unberührt");
        for (Path file : written) {
            assertTrue(Eula.accepted(Files.readString(file)), file.toString());
            assertTrue(Files.readString(file).contains(Eula.URL), "die Datei nennt die EULA");
        }
        assertFalse(Files.exists(withoutEula.resolve("eula.txt")));
    }

    @Test
    void revokeMarksTheRefusalInsteadOfDeletingTheFile() throws Exception {
        Path lobby = service("static/Lobby-1");
        Eula.accept(cloud);

        Eula.revoke(cloud);

        assertEquals(Eula.State.REFUSED, Eula.status(cloud));
        assertTrue(Files.exists(Eula.file(cloud)), "eine gelöschte Datei ließe reapply wieder 'true' schreiben");
        assertFalse(Eula.accepted(Files.readString(lobby.resolve("eula.txt"))));
        assertTrue(Files.readString(lobby.resolve("eula.txt")).contains("eula=false"));
    }

    @Test
    void statusReadsTheFirstEulaLineNotJustTheWord() throws Exception {
        Files.createDirectories(Eula.file(cloud).getParent());
        Files.writeString(Eula.file(cloud), "# eula=true steht hier nur als Kommentar\neula=false\n");

        assertEquals(Eula.State.REFUSED, Eula.status(cloud));
        assertTrue(Eula.accepted("eula = true\n"), "Leerzeichen um das Gleichheitszeichen sind erlaubt");
        assertFalse(Eula.accepted("eula=false\n"));
        assertFalse(Eula.accepted(""));
    }

    private Path service(String relative) throws Exception {
        Path directory = Files.createDirectories(cloud.resolve(relative));
        Files.writeString(directory.resolve("eula.txt"), "eula=false\n");
        return directory;
    }
}
