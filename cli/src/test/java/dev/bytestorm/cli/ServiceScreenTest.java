package dev.bytestorm.cli;

import dev.bytestorm.proto.ServiceState;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceScreenTest {

    private static final int DETACH = 0x1D;

    private final ServiceCommandLineTest.FakeController controller =
            new ServiceCommandLineTest.FakeController();

    @Test
    void streamsLogsAndForwardsCommands() throws Exception {
        controller.logs = List.of("[12:00:00] [OUT] ready");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PipedInputStream input = new PipedInputStream();
        PipedOutputStream commands = new PipedOutputStream(input);
        Thread writer = Thread.ofVirtual().start(() -> {
            try (Writer out = new OutputStreamWriter(commands, StandardCharsets.UTF_8)) {
                Thread.sleep(100);
                out.write("say hi");
                out.write('\r');
                out.flush();
                Thread.sleep(300);
                out.write(DETACH);
                out.flush();
            } catch (Exception e) {
                throw new IllegalStateException("Testeingaben fehlgeschlagen", e);
            }
        });

        Terminal terminal = terminal(input, output);
        try {
            new ServiceScreen(controller, terminal).run("Lobby-1");
            awaitText(output, "Screen Lobby-1 beendet");
        } finally {
            terminal.close();
        }
        writer.join(5_000);

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("ready"), text);
        assertTrue(text.contains("Screen Lobby-1 beendet"), text);
        assertEquals(List.of("exec:" + controller.info.getId() + ":say hi"), controller.calls);
    }

    @Test
    @Timeout(10)
    void endsWhenTheServiceStops() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PipedInputStream input = new PipedInputStream();
        // Die Pipe bleibt offen: Der Screen muss ohne Eingabe und ohne Strg+] enden.
        PipedOutputStream commands = new PipedOutputStream(input);
        Terminal terminal = terminal(input, output);
        String text;
        try {
            controller.info = controller.info.toBuilder().setState(ServiceState.STOPPED).build();
            new ServiceScreen(controller, terminal).run("Lobby-1");
            // JLine reicht den Strom im Hintergrund weiter; erst wenn die letzte
            // Zeile angekommen ist, ist der Mitschnitt vollständig.
            awaitText(output, "Screen Lobby-1 beendet");
            text = output.toString(StandardCharsets.UTF_8);
        } finally {
            terminal.close();
            commands.close();
        }

        assertTrue(text.contains("Service Lobby-1 ist STOPPED"), text);
        assertTrue(text.contains("Screen Lobby-1 beendet"), text);
    }

    @Test
    void rejectsUnknownServices() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = terminal(new ByteArrayInputStream(new byte[0]), output);
        try {
            new ServiceScreen(controller, terminal).run("missing");
            awaitText(output, "Unbekannter Service");
        } finally {
            terminal.close();
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Unbekannter Service"), output.toString());
    }

    @Test
    void detectsNewLinesWhenTheRingBufferMoves() {
        assertEquals(List.of(), ServiceScreen.newLines(List.of("a", "b"), List.of("a", "b")));
        assertEquals(List.of("c"), ServiceScreen.newLines(List.of("a", "b"), List.of("b", "c")));
        assertEquals(List.of("b", "c"), ServiceScreen.newLines(List.of("a"), List.of("a", "b", "c")));
        assertEquals(List.of("x"), ServiceScreen.newLines(List.of("a", "b"), List.of("x")));
    }

    private static void awaitText(ByteArrayOutputStream output, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (output.toString(StandardCharsets.UTF_8).contains(expected)) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).contains(expected), output.toString(StandardCharsets.UTF_8));
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
