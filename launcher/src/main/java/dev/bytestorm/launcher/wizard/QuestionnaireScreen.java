package dev.bytestorm.launcher.wizard;

import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;

/**
 * Bedient den Fragebogen im Terminal: zeichnen, Taste lesen, Zustand ändern (§5.1).
 *
 * <p>Die Schleife ist bewusst schmal: Sie übersetzt nur Tasten in Aufrufe des
 * {@link QuestionnaireState} und zeichnet danach neu. Die Tabelle der Tasten
 * folgt der Konzeptvorgabe — Pfeile bewegen, {@code Enter} wählt, {@code ←}/{@code →}
 * klappen auf und zu, {@code Tab} springt zwischen Fragen, {@code Esc} schließt
 * erst die offene Frage und beendet danach die Einrichtung.
 *
 * <p>Liegt der Fokus in einem Eingabefeld, gehen druckbare Zeichen in den Text;
 * dann wirken die Kürzel nicht, die sonst belegt sind.
 */
final class QuestionnaireScreen {

    /** Cursor nach oben links; der Bildschirm wird nicht geleert (kein Flackern). */
    private static final String CURSOR_HOME = "\u001B[H";
    private static final String ERASE_LINE = "\u001B[K";
    private static final String ERASE_DOWN = "\u001B[J";
    /** Im Raw-Modus übersetzt das Terminal kein {@code \n}; der Wagenrücklauf gehört dazu. */
    private static final String NEWLINE = "\r\n";
    private static final int DEFAULT_WIDTH = 80;
    private static final int DEFAULT_HEIGHT = 24;
    private static final int EOF = -1;
    private static final int NONE = 0;
    private static final int ESCAPE = 27;
    private static final int INTERRUPT = 3;
    private static final int END_OF_FILE = 4;
    private static final int TAB = 9;
    private static final int ENTER = 13;
    private static final int BACKSPACE = 127;
    private static final int BACKSPACE_ALT = 8;
    private static final int UP = -2;
    private static final int DOWN = -3;
    private static final int LEFT = -4;
    private static final int RIGHT = -5;
    private static final int HOME = -6;
    private static final int END = -7;
    private static final int SHIFT_TAB = -8;
    private static final long SEQUENCE_TIMEOUT = 15;

    private final Terminal terminal;
    private final QuestionnaireState state;
    private boolean exitArmed;

    /**
     * @param terminal Terminal
     * @param form     Fragebogen
     * @param initial  bereits gegebene Antworten
     * @param notice   Meldung, die den Lauf ausgelöst hat
     */
    QuestionnaireScreen(Terminal terminal, Questionnaire form, Answers initial, QuestionnaireNotice notice) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.state = new QuestionnaireState(form, initial, notice);
    }

    /**
     * Zeigt den Fragebogen, bis er abgeschickt oder abgebrochen wird.
     *
     * @return die Antworten
     * @throws IllegalStateException  wenn das Terminal keinen Raw-Modus erlaubt
     * @throws WizardAbortedException bei Strg+C, Strg+D, EOF oder bestätigtem Esc
     */
    Answers run() {
        Attributes previous;
        try {
            previous = terminal.enterRawMode();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Das Terminal unterstützt keinen Raw-Modus", e);
        }
        try {
            while (true) {
                draw();
                int key = readKey();
                if (key == EOF) {
                    throw new WizardAbortedException("Einrichtung abgebrochen");
                }
                if (handle(key)) {
                    return state.answers();
                }
            }
        } catch (IOException e) {
            throw new WizardAbortedException("Einrichtung abgebrochen");
        } finally {
            terminal.setAttributes(previous);
            synchronized (this) {
                terminal.writer().println();
                terminal.writer().flush();
                terminal.flush();
            }
        }
    }

    /**
     * @param key gelesene Taste
     * @return {@code true}, wenn das Formular gültig abgeschickt wurde
     */
    private boolean handle(int key) {
        if (key != ESCAPE) {
            disarm();
        }
        switch (key) {
            case INTERRUPT, END_OF_FILE -> throw new WizardAbortedException("Einrichtung abgebrochen");
            case UP -> state.move(-1);
            case DOWN -> state.move(1);
            case LEFT -> state.collapse();
            case RIGHT -> state.expand();
            case TAB -> state.moveQuestion(true);
            case SHIFT_TAB -> state.moveQuestion(false);
            case HOME -> state.first();
            case END -> state.last();
            case ENTER -> {
                return state.enter();
            }
            case ESCAPE -> {
                return escape();
            }
            case BACKSPACE, BACKSPACE_ALT -> state.backspace();
            case NONE -> {
                // Unbekannte Steuersequenz: nicht interpretieren.
            }
            default -> {
                if (key == ' ' && !state.editingText()) {
                    state.toggleQuestion();
                    state.select();
                } else if (key >= 32) {
                    state.append((char) key);
                }
            }
        }
        return false;
    }

    /**
     * Schließt die offene Frage; ohne offene Frage beendet Esc die Einrichtung.
     * Mit bereits gegebenen Antworten erst nach einer Rückfrage, damit ein
     * versehentlicher Tastendruck nichts verwirft.
     */
    private boolean escape() {
        if (state.collapse()) {
            exitArmed = false;
            return false;
        }
        if (!exitArmed && !state.answers().ids().isEmpty()) {
            exitArmed = true;
            state.notify("Esc noch einmal beendet die Einrichtung ohne Speichern.");
            return false;
        }
        throw new WizardAbortedException("Einrichtung abgebrochen");
    }

    private void disarm() {
        if (exitArmed) {
            exitArmed = false;
            state.clearNotice();
        }
    }

    private int readKey() throws IOException {
        int first = terminal.reader().read();
        if (first != ESCAPE) {
            return first;
        }
        int second = terminal.reader().read(SEQUENCE_TIMEOUT);
        if (second < 0) {
            return ESCAPE;
        }
        if (second != '[' && second != 'O') {
            return NONE;
        }
        return sequence();
    }

    private int sequence() throws IOException {
        int third = terminal.reader().read(SEQUENCE_TIMEOUT);
        if (third < 0) {
            return ESCAPE;
        }
        switch (third) {
            case 'A':
                return UP;
            case 'B':
                return DOWN;
            case 'C':
                return RIGHT;
            case 'D':
                return LEFT;
            case 'H':
                return HOME;
            case 'F':
                return END;
            case 'Z':
                return SHIFT_TAB;
            default:
                break;
        }
        StringBuilder digits = new StringBuilder().append((char) third);
        while (true) {
            int next = terminal.reader().read(SEQUENCE_TIMEOUT);
            if (next < 0) {
                return NONE;
            }
            if (next == '~') {
                break;
            }
            if (!Character.isDigit(next) || digits.length() >= 3) {
                return NONE;
            }
            digits.append((char) next);
        }
        return switch (digits.toString()) {
            case "1", "7" -> HOME;
            case "4", "8" -> END;
            default -> NONE;
        };
    }

    private void draw() {
        Size size = terminal.getSize();
        int width = size.getColumns() > 0 ? size.getColumns() : DEFAULT_WIDTH;
        int height = size.getRows() > 0 ? size.getRows() : DEFAULT_HEIGHT;
        List<String> lines = QuestionnaireRenderer.render(state.form(), state, width, height);
        synchronized (this) {
            PrintWriter writer = terminal.writer();
            // Neu zeichnen statt löschen: Jede Zeile wird überschrieben und bis zum
            // Zeilenende geleert. Ein volles Löschen erzeugt bei jedem Tastendruck
            // einen sichtbaren schwarzen Zwischenzustand — das Flackern.
            writer.print(CURSOR_HOME);
            for (String line : lines) {
                writer.print(line);
                writer.print(ERASE_LINE);
                writer.print(NEWLINE);
            }
            writer.print(ERASE_DOWN);
            writer.flush();
            terminal.flush();
        }
    }
}
