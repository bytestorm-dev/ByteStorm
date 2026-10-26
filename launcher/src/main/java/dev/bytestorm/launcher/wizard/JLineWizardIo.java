package dev.bytestorm.launcher.wizard;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Terminal-Ein-/Ausgabe des Wizards über JLine (§5.1, §17).
 *
 * <p>Strg+C und Strg+D brechen ab und werden als
 * {@link WizardAbortedException} gemeldet, damit keine Konfiguration
 * geschrieben wird.
 */
public final class JLineWizardIo implements WizardIo, AutoCloseable {

    private final Terminal terminal;
    private final LineReader reader;

    /**
     * @throws IOException wenn das Terminal nicht geöffnet werden kann
     */
    public JLineWizardIo() throws IOException {
        this.terminal = TerminalBuilder.builder().system(true).build();
        this.reader = LineReaderBuilder.builder().terminal(terminal).build();
    }

    @Override
    public String ask(String prompt, String defaultValue) {
        String answer = read(prompt + (defaultValue == null ? "" : " [" + defaultValue + "]") + ": ").trim();
        return answer.isEmpty() ? defaultValue : answer;
    }

    @Override
    public String askSecret(String prompt) {
        // Nicht trimmen: Leerzeichen dürfen Teil eines Passworts sein.
        return readSecret(prompt + ": ");
    }

    @Override
    public String choose(String prompt, List<String> options, String defaultValue) {
        while (true) {
            String answer = ask(prompt + " (" + String.join("/", options) + ")", defaultValue);
            for (String option : options) {
                if (option.equalsIgnoreCase(answer)) {
                    return option;
                }
            }
            message("Bitte eine der Optionen wählen: " + String.join(", ", options));
        }
    }

    @Override
    public boolean confirm(String prompt, boolean defaultYes) {
        while (true) {
            String answer = ask(prompt + " (j/n)", defaultYes ? "j" : "n").trim().toLowerCase(Locale.ROOT);
            switch (answer) {
                case "j", "ja", "y", "yes" -> {
                    return true;
                }
                case "n", "nein", "no" -> {
                    return false;
                }
                default -> message("Bitte j oder n eingeben.");
            }
        }
    }

    @Override
    public void message(String text) {
        terminal.writer().println(text);
        terminal.flush();
    }

    @Override
    public void close() throws IOException {
        terminal.close();
    }

    private String read(String prompt) {
        try {
            String line = reader.readLine(prompt);
            return line == null ? "" : line;
        } catch (UserInterruptException | EndOfFileException e) {
            throw new WizardAbortedException("Einrichtung abgebrochen");
        }
    }

    private String readSecret(String prompt) {
        try {
            String line = reader.readLine(prompt, '*');
            return line == null ? "" : line;
        } catch (UserInterruptException | EndOfFileException e) {
            throw new WizardAbortedException("Einrichtung abgebrochen");
        }
    }
}
