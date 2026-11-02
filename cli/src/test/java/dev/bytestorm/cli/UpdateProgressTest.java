package dev.bytestorm.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateProgressTest {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);

    @Test
    void drawstheLineInPlaceAndEndsWithANewline() {
        UpdateProgress progress = new UpdateProgress(out, true);

        progress.onProgress(0, 1_000);
        progress.onProgress(1_000, 1_000);

        String text = output();
        assertTrue(text.contains("\r"), text);
        assertTrue(text.endsWith(System.lineSeparator()), text);
        assertTrue(text.contains("100 %"), text);
        assertTrue(text.contains("1000 B / 1000 B"), text);
        assertTrue(text.contains("#") || text.contains("█"), text);
    }

    @Test
    void redirectedOutputGetsOnlyTheFinalLine() {
        UpdateProgress progress = new UpdateProgress(out, false);

        progress.onProgress(0, 1_000);
        progress.onProgress(500, 1_000);
        progress.onProgress(1_000, 1_000);

        String text = output();
        assertFalse(text.contains("\r"), text);
        assertEquals(1, text.lines().count(), text);
        assertTrue(text.contains("100 %"), text);
    }

    @Test
    void withoutContentLengthOnlyTheBytesAreShown() {
        UpdateProgress progress = new UpdateProgress(out, true);

        progress.onProgress(4_096, -1);
        progress.onProgress(8_192, -1);

        String text = output();
        assertTrue(text.contains("Download"), text);
        assertTrue(text.contains("KiB"), text);
        assertFalse(text.contains("%"), text);
    }

    @Test
    void sizesUseBinaryUnitsAndGermanDecimalComma() {
        assertEquals("0 B", UpdateProgress.size(0));
        assertEquals("1023 B", UpdateProgress.size(1_023));
        assertEquals("1,0 KiB", UpdateProgress.size(1_024));
        assertEquals("103,8 MiB", UpdateProgress.size(108_842_189));
    }

    private String output() {
        out.flush();
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
