package dev.bytestorm.launcher;

import dev.bytestorm.cli.Theme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BannerTest {

    private final boolean previous = Theme.enabled();

    @AfterEach
    void restoreColors() {
        Theme.setEnabled(previous);
    }

    @Test
    void readsTheFirstRenderingAndSkipsTheNotes(@TempDir Path directory) throws IOException {
        Path assets = Files.createDirectories(directory.resolve("assets"));
        Files.writeString(assets.resolve(Banner.FILE.substring("assets/".length())), String.join("\n",
                "██████╗ ██╗",
                "██╔══██╗╚██╗",
                "                (loaded)",
                "",
                "██████  ██",
                "██   ██   ██",
                "                (unloaded)",
                ""), StandardCharsets.UTF_8);

        assertEquals(List.of("██████╗ ██╗", "██╔══██╗╚██╗"), Banner.read(directory));
    }

    @Test
    void startsWithoutABannerWhenTheFileIsMissing(@TempDir Path directory) {
        assertEquals(List.of(), Banner.read(directory));
        assertEquals(List.of(), Banner.read(null));
    }

    @Test
    void revealsCharacterByCharacterFromTheLeft() {
        Theme.setEnabled(false);
        List<String> lines = List.of("abcd", "efgh");

        assertEquals(List.of("    ", "    "), Banner.reveal(lines, 0));
        assertEquals(List.of("abcd", "    "), Banner.reveal(lines, 0.5));
        assertEquals(List.of("abcd", "efgh"), Banner.reveal(lines, 1));
        // Begrenzt auch außerhalb des gültigen Bereichs, statt zu werfen.
        assertEquals(List.of("abcd", "efgh"), Banner.reveal(lines, 4));
    }

    @Test
    void centersInTheTerminalWidth() {
        List<String> lines = List.of("abcd", "efgh");

        assertEquals(List.of("   abcd", "   efgh"), Banner.center(lines, 10));
        // Schmaler als das Banner: bleibt links, statt über den Rand zu laufen.
        assertEquals(lines, Banner.center(lines, 2));
        // Unbekannte Breite: unverändert.
        assertEquals(lines, Banner.center(lines, 0));
    }

    @Test
    void keepsTheWidthWhileGrowing() {
        List<String> lines = List.of("abcdef");

        List<String> half = Banner.reveal(lines, 0.5);

        assertEquals(6, half.get(0).length());
        assertEquals("abc   ", half.get(0));
    }
}
