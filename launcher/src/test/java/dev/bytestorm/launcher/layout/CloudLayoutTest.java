package dev.bytestorm.launcher.layout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudLayoutTest {

    @TempDir
    Path temp;

    @Test
    void createsAllDirectoriesIdempotently() throws IOException {
        CloudLayout layout = new CloudLayout(temp.resolve("cloud"));

        layout.create();
        layout.create();

        for (String directory : CloudLayout.DIRECTORIES) {
            assertTrue(Files.isDirectory(layout.root().resolve(directory)), directory);
        }
        assertTrue(Files.isDirectory(layout.groups()));
        assertTrue(Files.isDirectory(layout.modules()));
        assertTrue(Files.isDirectory(layout.templates().resolve("ALL")));
        assertTrue(Files.isDirectory(layout.templates().resolve("PROXY")));
        assertTrue(Files.isDirectory(layout.templates().resolve("SERVER")));
        assertTrue(Files.isDirectory(layout.templates().resolve("FALLBACK")));
    }

    @Test
    void exposesTheDocumentedPaths() {
        CloudLayout layout = new CloudLayout(temp);

        assertEquals(temp.toAbsolutePath().normalize(), layout.root());
        assertEquals(layout.root().resolve("lib"), layout.lib());
        assertEquals(layout.root().resolve("config"), layout.config());
        assertEquals(layout.root().resolve("config/groups"), layout.groups());
        assertEquals(layout.root().resolve("config/modules"), layout.modules());
        assertEquals(layout.root().resolve("templates"), layout.templates());
        assertEquals(layout.root().resolve("platforms"), layout.platforms());
        assertEquals(layout.root().resolve("running"), layout.running());
        assertEquals(layout.root().resolve("static"), layout.staticServices());
        assertEquals(layout.root().resolve("crash-reports"), layout.crashReports());
        assertEquals(layout.root().resolve("logs"), layout.logs());
    }

    @Test
    void normalisesRelativeRoots() {
        CloudLayout layout = new CloudLayout(Path.of("cloud"));

        assertTrue(layout.root().isAbsolute());
        assertEquals(Path.of("cloud").toAbsolutePath().normalize(), layout.root());
    }

    @Test
    void rejectsMissingRoot() {
        assertThrows(NullPointerException.class, () -> new CloudLayout(null));
    }
}
