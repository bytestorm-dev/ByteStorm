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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void composesTheBoltNextToTheStaticLogo() {
        Theme.setEnabled(false);
        List<String> logo = List.of("██", "██");

        List<String> struck = Banner.compose(logo, 1);
        List<String> quiet = Banner.compose(logo, 0);

        // Der Blitz hängt rechts daneben; beide Bilder sind gleich breit, damit die
        // Mitte zwischen zwei Bildern nicht springt.
        assertEquals(2, struck.size());
        assertEquals(struck.get(0).length(), struck.get(1).length());
        assertEquals(quiet.get(0).length(), struck.get(0).length());
        assertTrue(struck.get(0).startsWith("██"), struck.get(0));
        assertTrue(struck.get(0).length() > quiet.get(0).stripTrailing().length(),
                "mit Blitz steht rechts mehr als ohne: " + struck.get(0));
        assertEquals("██", quiet.get(0).stripTrailing(), "ohne Blitz bleibt nur das Logo");
    }

    @Test
    void shadowIsDarkerThanTheFront() {
        Theme.setEnabled(true);

        String front = Banner.paintLogo("██");
        String shadow = Banner.paintLogo("╗╗");

        assertEquals(Banner.FRONT_COLOR + "██" + "\u001B[0m", front);
        assertEquals(Banner.SHADOW_COLOR + "╗╗" + "\u001B[0m", shadow);
        assertNotEquals(Banner.FRONT_COLOR, Banner.SHADOW_COLOR);
        // Zwei Stufen dunkler, nicht nur anders: 37 ist die Front, 30 der Schatten.
        assertTrue(Banner.SHADOW_COLOR.compareTo(Banner.FRONT_COLOR) != 0, "eigene Farbe");
    }

    @Test
    void centersColoredLinesByTheirVisibleWidth() {
        Theme.setEnabled(true);

        List<String> centered = Banner.center(List.of(Banner.paintLogo("██")), 10);

        assertEquals("    ██", Theme.stripAnsi(centered.get(0)));
        // Schmaler als das Banner: bleibt links, statt über den Rand zu laufen.
        assertEquals(List.of("██████"), Banner.center(List.of("██████"), 2).stream()
                .map(Theme::stripAnsi).toList());
        // Unbekannte Breite: unverändert.
        assertFalse(Banner.center(List.of("ok"), 0).isEmpty());
    }
}
