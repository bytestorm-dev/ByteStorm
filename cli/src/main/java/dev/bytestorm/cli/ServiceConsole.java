package dev.bytestorm.cli;

import dev.bytestorm.controller.ServiceController;
import dev.bytestorm.proto.ServiceInfo;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Interaktive JLine-Konsole für die Service-Kommandos (§17).
 *
 * <p>Die Konsole ist nur Ein-/Ausgabe: Sie liest eine Zeile, reicht sie an
 * {@link ServiceCommandLine} weiter und schreibt das Ergebnis ins Terminal.
 * {@code exit}, {@code quit}, {@code shutdown}, Strg+C und Strg+D beenden die
 * Konsole; der Aufrufer fährt danach die Cloud herunter.
 */
public final class ServiceConsole implements AutoCloseable {

    /** Dateiname der persistenten History im Log-Verzeichnis der Cloud. */
    public static final String HISTORY_FILE_NAME = ".cli-history";

    /** Prompt der Konsole, fett cyan mit gedämpftem Zeichen. */
    public static final String PROMPT =
            "\u001B[1;36mbytestorm\u001B[0m \u001B[2m·\u001B[0m \u001B[92m›\u001B[0m ";

    /** Prompt ohne Farben, etwa bei {@code --no-color} oder an einer Pipe. */
    private static final String PROMPT_PLAIN = "bytestorm> ";

    private static final int HISTORY_SIZE = 2_000;
    private static final int HISTORY_FILE_SIZE = 10_000;

    private static final List<String> COMMANDS = List.of(
            "help",
            "info",
            "status",
            "watch",
            "clear",
            "service",
            "list",
            "info",
            "start",
            "stop",
            "restart",
            "kill",
            "reapply",
            "exec",
            "screen",
            "online",
            "heartbeat",
            "heartbeat-check",
            "logs",
            "degraded",
            "group",
            "list",
            "create",
            "edit",
            "delete",
            "scale",
            "maintenance",
            "templates",
            "template",
            "copy",
            "sync",
            "player",
            "connect",
            "kick",
            "on",
            "off",
            "module",
            "enable",
            "disable",
            "platform",
            "tasks",
            "versions",
            "install",
            "update",
            "updater",
            "check",
            "reload",
            "exit",
            "quit",
            "shutdown",
            "stop");

    private final ServiceCommandLine commandLine;
    private final ServiceController controller;
    private final Terminal terminal;
    private final Path historyFile;
    private final LineReader reader;

    /**
     * Öffnet das Systemterminal mit persistenter History.
     *
     * @param commandLine Service-Kommandos
     * @param controller  Controller für dynamische Vervollständigung
     * @param historyFile Datei für die History, {@code null} für nur in-memory
     * @throws IOException wenn kein Terminal geöffnet werden kann
     */
    public ServiceConsole(ServiceCommandLine commandLine, ServiceController controller, Path historyFile)
            throws IOException {
        this(commandLine, controller, historyFile, TerminalBuilder.builder().system(true).build());
    }

    ServiceConsole(ServiceCommandLine commandLine, ServiceController controller, Terminal terminal) {
        this(commandLine, controller, null, terminal);
    }

    ServiceConsole(ServiceCommandLine commandLine, ServiceController controller, Path historyFile,
                   Terminal terminal) {
        this.commandLine = Objects.requireNonNull(commandLine, "commandLine");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.historyFile = historyFile == null ? null : historyFile.toAbsolutePath().normalize();
        // Farben erst hier schalten: Die Konsole kennt als einzige den Terminaltyp.
        Theme.apply(terminal.getType());

        LineReaderBuilder builder = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(this::complete)
                .variable(LineReader.HISTORY_SIZE, HISTORY_SIZE)
                .variable(LineReader.HISTORY_FILE_SIZE, HISTORY_FILE_SIZE);
        if (this.historyFile != null) {
            builder.variable(LineReader.HISTORY_FILE, this.historyFile);
        }
        this.reader = builder.build();
        if (this.reader.getHistory() instanceof DefaultHistory history) {
            // JLine hängt die History erst beim ersten readLine an; für Persistenz
            // und Tests muss sie schon beim Aufbau geladen sein.
            history.attach(this.reader);
        }
    }

    /**
     * Liest Kommandos, bis der Benutzer die Konsole beendet.
     */
    public void run() {
        terminal.writer().println(welcome());
        terminal.flush();
        while (true) {
            String line;
            try {
                line = reader.readLine(Theme.enabled() ? PROMPT : PROMPT_PLAIN);
            } catch (UserInterruptException | EndOfFileException e) {
                return;
            }
            if (line == null) {
                return;
            }
            String input = line.trim();
            if (input.isEmpty()) {
                continue;
            }
            if (isExit(input)) {
                return;
            }
            if (handleClear(input)) {
                continue;
            }
            if (handleScreen(input)) {
                continue;
            }
            if (handleWatch(input)) {
                continue;
            }

            String output;
            try {
                output = commandLine.execute(input);
            } catch (RuntimeException e) {
                output = Theme.fail("Fehler: " + messageOf(e));
            }
            if (!output.isEmpty()) {
                terminal.writer().println(output);
                // Eine ruhige Leerzeile trennt Antwort und nächsten Prompt.
                terminal.writer().println();
            }
            terminal.flush();
        }
    }

    /**
     * @return Begrüßung als Rahmen mit den wichtigsten Einstiegen
     */
    private String welcome() {
        String nl = System.lineSeparator();
        int width = 66;
        int inner = width - 2;
        boolean fancy = Theme.unicode();
        String horizontal = fancy ? "─" : "-";
        String edge = Theme.accent(fancy ? "│" : "|");
        String top = Theme.accent((fancy ? "╭" : "+") + horizontal.repeat(inner) + (fancy ? "╮" : "+"));
        String bottom = Theme.accent((fancy ? "╰" : "+") + horizontal.repeat(inner) + (fancy ? "╯" : "+"));

        StringBuilder out = new StringBuilder();
        out.append(top).append(nl);
        out.append(edge).append(fill(Theme.bold("  ByteStorm") + Theme.dim("  ·  Cloud Console"), inner))
                .append(edge).append(nl);
        out.append(edge).append(fill(Theme.dim("  Services, Gruppen und Logs im Blick."), inner))
                .append(edge).append(nl);
        out.append(edge).append(fill("  " + Theme.accent("status") + Theme.dim(" Übersicht   ")
                + Theme.accent("watch") + Theme.dim(" live   ")
                + Theme.accent("help") + Theme.dim(" Kommandos   ")
                + Theme.accent("exit") + Theme.dim(" beenden"), inner)).append(edge).append(nl);
        out.append(bottom).append(nl);
        return out.toString();
    }

    /**
     * @param styled bereits gefärbter Text
     * @param width  sichtbare Zielbreite
     * @return der Text, rechts auf die Breite aufgefüllt
     */
    private static String fill(String styled, int width) {
        int visible = Theme.stripAnsi(styled).length();
        return visible >= width ? styled : styled + " ".repeat(width - visible);
    }

    /**
     * Öffnet die fortlaufende Übersicht für {@code watch [sekunden]}.
     *
     * @param input eingegebene Zeile
     * @return {@code true}, wenn die Zeile hier behandelt wurde
     */
    private boolean handleWatch(String input) {
        String[] tokens = input.split("\\s+");
        if (!tokens[0].equalsIgnoreCase("watch")) {
            return false;
        }
        long interval;
        try {
            interval = Dashboard.intervalMillis(tokens.length > 1 ? tokens[1] : null);
        } catch (IllegalArgumentException e) {
            terminal.writer().println(Theme.warn(e.getMessage()));
            terminal.flush();
            return true;
        }
        new DashboardScreen(terminal, commandLine::dashboardSnapshot, interval).run();
        return true;
    }

    /**
     * @param prefix bisher getipptes Wort
     * @return passende Kommandos und Service-Namen, sortiert
     */
    List<String> completions(String prefix) {
        String word = prefix == null ? "" : prefix;
        Set<String> values = new TreeSet<>();
        for (String command : COMMANDS) {
            if (command.regionMatches(true, 0, word, 0, word.length())) {
                values.add(command);
            }
        }
        try {
            for (ServiceInfo info : controller.services()) {
                if (info.getName().regionMatches(true, 0, word, 0, word.length())) {
                    values.add(info.getName());
                }
            }
            for (String group : commandLine.groupNames()) {
                if (group.regionMatches(true, 0, word, 0, word.length())) {
                    values.add(group);
                }
            }
            for (String template : commandLine.templateNames()) {
                if (template.regionMatches(true, 0, word, 0, word.length())) {
                    values.add(template);
                }
            }
            for (String player : commandLine.playerNames()) {
                if (player.regionMatches(true, 0, word, 0, word.length())) {
                    values.add(player);
                }
            }
        } catch (RuntimeException e) {
            // Die Konsole darf an einer fehlgeschlagenen Vervollständigung nicht sterben.
        }
        return List.copyOf(values);
    }

    /**
     * @return die geladenen History-Zeilen, älteste zuerst
     */
    List<String> history() {
        List<String> entries = new ArrayList<>();
        for (History.Entry entry : reader.getHistory()) {
            entries.add(entry.line());
        }
        return List.copyOf(entries);
    }

    /**
     * Speichert die History und schließt das Terminal.
     *
     * @throws IOException wenn History oder Terminal nicht geschlossen werden können
     */
    @Override
    public void close() throws IOException {
        try {
            if (historyFile != null) {
                reader.getHistory().save();
            }
        } finally {
            terminal.close();
        }
    }

    private boolean handleClear(String input) {
        if (!input.equalsIgnoreCase("clear")) {
            return false;
        }
        terminal.writer().print("\u001B[2J\u001B[3J\u001B[H");
        terminal.writer().flush();
        terminal.flush();
        return true;
    }

    private boolean handleScreen(String input) {
        String[] tokens = input.split("\\s+");
        if (tokens.length < 2
                || !tokens[0].equalsIgnoreCase("service")
                || !tokens[1].equalsIgnoreCase("screen")) {
            return false;
        }
        if (tokens.length < 3) {
            terminal.writer().println(Theme.warn("Service-Name fehlt (service screen <name>)"));
            terminal.flush();
            return true;
        }
        new ServiceScreen(controller, terminal).run(tokens[2]);
        return true;
    }

    private void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        for (String completion : completions(line.word())) {
            candidates.add(new Candidate(completion));
        }
    }

    private static boolean isExit(String input) {
        String command = input.toLowerCase(Locale.ROOT);
        return command.equals("exit") || command.equals("quit")
                || command.equals("shutdown") || command.equals("stop");
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
