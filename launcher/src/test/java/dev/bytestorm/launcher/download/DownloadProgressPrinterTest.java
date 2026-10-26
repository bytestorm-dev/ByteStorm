package dev.bytestorm.launcher.download;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadProgressPrinterTest {

    private static final Dependency DEPENDENCY =
            new Dependency("com.example", "lib", "1.0", "a".repeat(64));

    @Test
    void printsOneLinePerFileWithoutTerminal() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        new DownloadProgressPrinter(out, false).accept(new DownloadStatus(1, 2, DEPENDENCY, true));

        assertEquals("[1/2] geladen: lib-1.0.jar" + System.lineSeparator(),
                bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void overwritesTheLineAtATerminalAndEndsWithNewline() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        DownloadProgressPrinter printer = new DownloadProgressPrinter(out, true);

        printer.accept(new DownloadStatus(1, 1, DEPENDENCY, false));

        String text = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.startsWith("\r"), text);
        assertTrue(text.contains("[1/1] vorhanden: lib-1.0.jar"), text);
        assertTrue(text.endsWith(System.lineSeparator()), text);
    }
}
