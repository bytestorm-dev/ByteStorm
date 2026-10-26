package dev.bytestorm.launcher.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibManifestTest {

    @TempDir
    Path temp;

    @Test
    void missingManifestReadsAsEmpty() throws IOException {
        assertTrue(LibManifest.read(temp.resolve("lib")).dependencies().isEmpty());
    }

    @Test
    void writesAndReadsTheManifest() throws IOException {
        LibManifest.Entry entry = new LibManifest.Entry(
                "lib-1.0.jar", "com.example", "lib", "1.0", "a".repeat(64), 42);
        LibManifest manifest = new LibManifest(List.of(entry));

        manifest.write(temp);

        Path file = temp.resolve(LibManifest.FILE_NAME);
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(temp.resolve(LibManifest.FILE_NAME + ".tmp")));
        assertTrue(Files.readString(file).contains("\"sha256\""));

        LibManifest loaded = LibManifest.read(temp);
        assertEquals(List.of(entry), loaded.dependencies());
        assertEquals(entry, loaded.find("lib-1.0.jar").orElseThrow());
        assertTrue(loaded.find("fehlt.jar").isEmpty());
    }

    @Test
    void rejectsInvalidEntries() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new LibManifest.Entry("f", "g", "a", "1", "a".repeat(64), -1));
    }
}
