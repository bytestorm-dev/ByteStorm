package dev.bytestorm.cli;

import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.controller.ServiceController;
import dev.bytestorm.node.lifecycle.ServiceLifecycleException;
import dev.bytestorm.proto.ServiceInfo;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interaktiver Screen eines Services (§17).
 *
 * <p>Zeigt neue Logzeilen aus dem Ringpuffer des Nodes und leitet Eingaben an
 * die Service-Konsole weiter. {@code Ctrl+]} oder {@code Ctrl+C} beenden den
 * Screen; der Service läuft weiter. Logs werden gepollt, damit der Screen ohne
 * zusätzliche Redis-Verbindung funktioniert.
 */
final class ServiceScreen {

    private static final int DETACH = 0x1D;
    private static final int INTERRUPT = 0x03;
    private static final long POLL_MILLIS = 200;

    private final ServiceController controller;
    private final Terminal terminal;
    private final StringBuilder input = new StringBuilder();
    private final Object output = new Object();
    private String serviceName = "";

    /**
     * @param controller Controller-Fassade
     * @param terminal   Terminal der laufenden Konsole
     */
    ServiceScreen(ServiceController controller, Terminal terminal) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
    }

    /**
     * Öffnet den Screen, bis der Benutzer ihn beendet.
     *
     * @param name Service-Name
     */
    void run(String name) {
        Optional<ServiceInfo> found = controller.byName(name);
        if (found.isEmpty() || found.get().getId().isEmpty()) {
            printLine(Theme.warn("Unbekannter Service: " + name));
            return;
        }
        ServiceId id = ServiceId.parse(found.get().getId());
        serviceName = found.get().getName();

        Attributes previous;
        try {
            previous = terminal.enterRawMode();
        } catch (RuntimeException e) {
            printLine(Theme.fail("Screen nicht verfügbar: " + messageOf(e)));
            return;
        }

        AtomicBoolean running = new AtomicBoolean(true);
        Thread follower = null;
        try {
            printLine(Theme.section("Screen " + serviceName)
                    + Theme.dim("  —  Strg+] oder Strg+C beendet"));
            follower = Thread.ofVirtual()
                    .name("bytestorm-screen-" + serviceName)
                    .start(() -> follow(id, running));
            renderPrompt();
            readCommands(id);
        } catch (IOException e) {
            // Terminal wurde geschlossen; der Screen endet.
        } finally {
            running.set(false);
            if (follower != null) {
                follower.interrupt();
                try {
                    follower.join(1_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            terminal.setAttributes(previous);
            synchronized (output) {
                PrintWriter writer = terminal.writer();
                writer.print("\r\n");
                writer.println(Theme.dim("Screen " + serviceName + " beendet."));
                writer.flush();
                terminal.flush();
            }
        }
    }

    /**
     * @param previous vorheriger Log-Snapshot
     * @param current  aktueller Log-Snapshot
     * @return Zeilen, die im aktuellen Snapshot neu hinzugekommen sind
     */
    static List<String> newLines(List<String> previous, List<String> current) {
        if (current.isEmpty()) {
            return List.of();
        }
        if (previous.isEmpty()) {
            return List.copyOf(current);
        }
        int overlap = Math.min(previous.size(), current.size());
        while (overlap > 0
                && !previous.subList(previous.size() - overlap, previous.size())
                .equals(current.subList(0, overlap))) {
            overlap--;
        }
        return List.copyOf(current.subList(overlap, current.size()));
    }

    private void follow(ServiceId id, AtomicBoolean running) {
        List<String> previous = List.of();
        while (running.get()) {
            List<String> current;
            try {
                current = controller.logs(id);
            } catch (RuntimeException e) {
                return;
            }
            for (String line : newLines(previous, current)) {
                printLog(line);
            }
            previous = current;
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void readCommands(ServiceId id) throws IOException {
        while (true) {
            int read = terminal.reader().read();
            if (read < 0 || read == DETACH || read == INTERRUPT) {
                return;
            }
            if (read == '\r' || read == '\n') {
                send(id);
            } else if (read == 127 || read == 8) {
                backspace();
            } else if (read >= 32) {
                append((char) read);
            }
        }
    }

    private void send(ServiceId id) {
        String command;
        synchronized (output) {
            command = input.toString().trim();
            input.setLength(0);
            PrintWriter writer = terminal.writer();
            writer.print("\r\n");
            writer.flush();
        }
        if (command.isEmpty()) {
            renderPrompt();
            return;
        }
        try {
            controller.sendCommand(id, command);
        } catch (ServiceLifecycleException | RuntimeException e) {
            printLine(Theme.fail("Fehler: " + messageOf(e)));
        }
        renderPrompt();
    }

    private void append(char character) {
        synchronized (output) {
            input.append(character);
            terminal.writer().print(character);
            terminal.writer().flush();
        }
    }

    private void backspace() {
        synchronized (output) {
            if (input.isEmpty()) {
                return;
            }
            input.setLength(input.length() - 1);
            terminal.writer().print("\b \b");
            terminal.writer().flush();
        }
    }

    private void printLog(String line) {
        synchronized (output) {
            PrintWriter writer = terminal.writer();
            writer.print("\r" + line + "\r\n");
            renderPromptLocked();
            writer.flush();
            terminal.flush();
        }
    }

    private void renderPrompt() {
        synchronized (output) {
            renderPromptLocked();
            terminal.writer().flush();
            terminal.flush();
        }
    }

    private void renderPromptLocked() {
        terminal.writer().print(Theme.cyan("screen " + serviceName) + "> " + input);
    }

    private void printLine(String line) {
        synchronized (output) {
            PrintWriter writer = terminal.writer();
            writer.println(line);
            writer.flush();
            terminal.flush();
        }
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
