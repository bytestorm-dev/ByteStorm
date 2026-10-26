package dev.bytestorm.cli;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceConsoleTest {

    @TempDir
    Path temp;

    private final ServiceCommandLineTest.FakeController controller =
            new ServiceCommandLineTest.FakeController();
    private final ServiceCommandLine commandLine = new ServiceCommandLine(controller);

    @Test
    void runsCommandsUntilExit() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        runConsole(List.of("help", "service list", "clear", "exit"), null, output);

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("service list"), text);
        assertTrue(text.contains("Lobby-1"), text);
        assertTrue(text.contains("\u001B[2J"), text);
        assertTrue(text.contains("bytestorm"), text);
        assertTrue(ServiceConsole.PROMPT.contains("\u001B[1;36m"));
    }

    @Test
    void persistsHistoryBetweenSessions() throws Exception {
        Path historyFile = temp.resolve(ServiceConsole.HISTORY_FILE_NAME);
        runConsole(List.of("help", "service list", "exit"), historyFile, new ByteArrayOutputStream());
        assertTrue(Files.exists(historyFile), "History-Datei fehlt: " + historyFile);

        try (ServiceConsole console = new ServiceConsole(commandLine, controller, historyFile,
                terminal(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()))) {
            List<String> history = console.history();
            String file = Files.readString(historyFile);
            assertTrue(history.contains("help"), history + " / Datei: " + file);
            assertTrue(history.contains("service list"), history + " / Datei: " + file);
        }
    }

    @Test
    void completesCommandsAndServiceNames() throws Exception {
        try (ServiceConsole console = new ServiceConsole(commandLine, controller,
                terminal(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()))) {
            assertTrue(console.completions("heart").contains("heartbeat"));
            assertTrue(console.completions("heart").contains("heartbeat-check"));
            assertTrue(console.completions("re").contains("restart"));
            assertTrue(console.completions("re").contains("reapply"));
            assertTrue(console.completions("Lob").contains("Lobby-1"));
            assertTrue(console.completions("").contains("service"));
        }
    }

    private void runConsole(List<String> commands, Path historyFile, ByteArrayOutputStream output)
            throws Exception {
        PipedInputStream input = new PipedInputStream();
        PipedOutputStream commandStream = new PipedOutputStream(input);
        Thread writer = Thread.ofVirtual().start(() -> {
            try (Writer out = new OutputStreamWriter(commandStream, StandardCharsets.UTF_8)) {
                for (String command : commands) {
                    out.write(command);
                    out.write('\r');
                    out.flush();
                    Thread.sleep(100);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Testeingaben fehlgeschlagen", e);
            }
        });

        try (ServiceConsole console = new ServiceConsole(commandLine, controller, historyFile,
                terminal(input, output))) {
            console.run();
        }
        writer.join(5_000);
        assertFalse(writer.isAlive(), "Testeingaben wurden nicht vollständig verarbeitet");
    }

    private static Terminal terminal(InputStream input, ByteArrayOutputStream output) throws Exception {
        return TerminalBuilder.builder()
                .system(false)
                .dumb(true)
                .type(Terminal.TYPE_DUMB)
                .streams(input, output)
                .build();
    }
}
