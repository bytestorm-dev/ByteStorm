package dev.bytestorm.launcher.layout;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServiceDirectoriesTest {

    @TempDir
    Path temp;

    private CloudLayout layout;

    @BeforeEach
    void setUp() throws Exception {
        layout = new CloudLayout(temp.resolve("cloud"));
        layout.create();
    }

    @Test
    void collectsStaticAndRunningNames() throws Exception {
        Files.createDirectories(layout.staticServices().resolve("Lobby-1"));
        Files.createDirectories(layout.running().resolve("Lobby-2-3f1c8c1e-1111-2222-3333-444455556666"));
        Files.createDirectories(layout.running().resolve("Proxy-1"));
        Files.writeString(layout.running().resolve("notizen.txt"), "kein Ordner");

        Set<String> names = ServiceDirectories.names(layout);

        assertEquals(Set.of("Lobby-1", "Lobby-2-3f1c8c1e-1111-2222-3333-444455556666", "Proxy-1"), names);
    }

    @Test
    void missingDirectoriesYieldNoNames() throws Exception {
        Files.delete(layout.running());
        Files.delete(layout.staticServices());

        assertEquals(Set.of(), ServiceDirectories.names(layout));
    }

    @Test
    void findsStaticAndDynamicServiceDirectories() throws Exception {
        Path fixed = Files.createDirectories(layout.staticServices().resolve("Proxy-1"));
        String id = "3f1c8c1e-1111-2222-3333-444455556666";
        Path dynamic = Files.createDirectories(layout.running().resolve("Lobby-1-" + id));

        assertEquals(Optional.of(fixed), ServiceDirectories.directory(layout, "Proxy-1"));
        assertEquals(Optional.of(dynamic), ServiceDirectories.directory(layout, "Lobby-1"));
    }

    @Test
    void doesNotMistakeAShortNameForALongerOne() throws Exception {
        String id = "3f1c8c1e-1111-2222-3333-444455556666";
        Files.createDirectories(layout.running().resolve("Lobby-10-" + id));
        Files.createDirectories(layout.running().resolve("Lobby-11"));

        assertEquals(Optional.empty(), ServiceDirectories.directory(layout, "Lobby-1"));
        assertEquals(Optional.empty(), ServiceDirectories.directory(layout, "Lobby-1-3f1c8c1e"),
                "ohne vollständige Service-ID wird nicht geraten");
        assertEquals(Optional.empty(), ServiceDirectories.directory(layout, " "));
    }
}
