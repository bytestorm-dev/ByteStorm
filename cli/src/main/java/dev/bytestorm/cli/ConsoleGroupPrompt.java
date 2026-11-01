package dev.bytestorm.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Fragt den Gruppen-Wizard über den {@link LineReader} der laufenden Konsole.
 *
 * <p>Derselbe Reader, der sonst Kommandos liest, stellt die Fragen — ein zweites
 * Terminal auf demselben TTY würde die Eingabezeile zerstören. Strg+C und EOF
 * brechen das Formular ab, statt die Konsole zu beenden; der aufrufende Befehl
 * entscheidet, was dann passiert.
 */
final class ConsoleGroupPrompt implements GroupWizard.Prompt {

    private final Terminal terminal;
    private final LineReader reader;

    /**
     * @param terminal Terminal der Konsole
     * @param reader   Reader der Konsole
     */
    ConsoleGroupPrompt(Terminal terminal, LineReader reader) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public Optional<String> ask(String question, String defaultValue) {
        return read(question, defaultValue);
    }

    @Override
    public Optional<String> choose(String question, List<String> options, String defaultValue) {
        while (true) {
            StringBuilder hint = new StringBuilder(Theme.dim("  "));
            for (int index = 0; index < options.size(); index++) {
                if (index > 0) {
                    hint.append(Theme.dim("  ·  "));
                }
                hint.append(Theme.accent(String.valueOf(index + 1))).append(") ").append(options.get(index));
            }
            note(hint.toString());
            Optional<String> answer = read(question, defaultValue);
            if (answer.isEmpty()) {
                return Optional.empty();
            }
            String value = answer.get().trim();
            try {
                int index = Integer.parseInt(value);
                if (index >= 1 && index <= options.size()) {
                    return Optional.of(options.get(index - 1));
                }
            } catch (NumberFormatException ignored) {
                // Kein Index: unten wird der Name geprüft.
            }
            for (String option : options) {
                if (option.equalsIgnoreCase(value)) {
                    return Optional.of(option);
                }
            }
            warn("Bitte eine Nummer zwischen 1 und " + options.size() + " oder einen Namen eingeben.");
        }
    }

    @Override
    public Optional<Boolean> confirm(String question, boolean defaultValue) {
        Optional<String> answer = read(question + " (j/n)", defaultValue ? "j" : "n");
        if (answer.isEmpty()) {
            return Optional.empty();
        }
        String value = answer.get().trim().toLowerCase(Locale.ROOT);
        return Optional.of(value.startsWith("j") || value.startsWith("y")
                || value.equals("1") || value.equals("true"));
    }

    @Override
    public void note(String message) {
        terminal.writer().println(message);
        terminal.flush();
    }

    @Override
    public void warn(String message) {
        terminal.writer().println(Theme.warn(message));
        terminal.flush();
    }

    /**
     * Liest eine Antwort; Enter übernimmt den Vorschlag. Nach jeder Antwort folgt
     * eine Leerzeile, damit Frage und Antwort nicht am nächsten Prompt kleben.
     */
    private Optional<String> read(String question, String defaultValue) {
        String prompt = Theme.accent("?") + " " + Theme.bold(question)
                + (defaultValue == null || defaultValue.isBlank()
                ? "" : Theme.dim(" [" + defaultValue + "]"))
                + Theme.dim(" › ");
        try {
            String line = reader.readLine(prompt);
            String value = line == null ? "" : line.trim();
            if (value.isEmpty() && defaultValue != null) {
                value = defaultValue;
            }
            terminal.writer().println();
            terminal.flush();
            return Optional.of(value);
        } catch (UserInterruptException | EndOfFileException e) {
            terminal.writer().println();
            terminal.flush();
            return Optional.empty();
        }
    }
}
