package dev.bytestorm.cli;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Fortlaufende Dashboard-Ansicht im Terminal ({@code watch}, §17).
 *
 * <p>Die Ansicht baut alle {@link Dashboard#DEFAULT_INTERVAL_MILLIS} Millisekunden
 * eine neue Momentaufnahme auf und zeichnet sie über den Bildschirm. {@code q},
 * {@code Esc} oder Strg+C beenden sie; die Cloud läuft weiter. Das Terminal wird
 * für die Dauer der Ansicht in den Raw-Modus gesetzt und danach exakt
 * zurückgestellt, damit die Konsole danach normal weiterläuft.
 *
 * <p>Wie {@link ServiceScreen} pollt die Ansicht, statt zusätzliche
 * Redis-Verbindungen zu öffnen.
 */
final class DashboardScreen {

    private static final String CLEAR = "\u001B[2J\u001B[3J\u001B[H";
    private static final String HOME = "\u001B[H";
    private static final String ERASE_LINE = "\u001B[K";
    private static final String ERASE_DOWN = "\u001B[J";
    private static final String NEWLINE = "\r\n";
    private static final int INTERRUPT = 0x03;
    private static final int ESCAPE = 0x1B;
    private static final int POLL_MILLIS = 50;

    private final Terminal terminal;
    private final Supplier<Dashboard.Snapshot> source;
    private final long intervalMillis;
    private final Object output = new Object();

    /**
     * @param terminal       Terminal der laufenden Konsole
     * @param source         liefert die aktuelle Momentaufnahme
     * @param intervalMillis Abstand zwischen zwei Aufbauten
     */
    DashboardScreen(Terminal terminal, Supplier<Dashboard.Snapshot> source, long intervalMillis) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.source = Objects.requireNonNull(source, "source");
        this.intervalMillis = Math.max(Dashboard.MIN_INTERVAL_MILLIS, intervalMillis);
    }

    /**
     * Zeigt die Ansicht, bis der Benutzer sie beendet.
     */
    void run() {
        Attributes previous;
        try {
            previous = terminal.enterRawMode();
        } catch (RuntimeException e) {
            printLine("Dashboard nicht verfügbar: " + messageOf(e));
            return;
        }

        try {
            while (true) {
                draw();
                if (awaitExit()) {
                    return;
                }
            }
        } catch (IOException e) {
            // Terminal geschlossen: die Ansicht endet.
        } finally {
            terminal.setAttributes(previous);
            printLine(CLEAR + Theme.dim("Dashboard beendet."));
        }
    }

    /**
     * Wartet das Intervall ab und prüft dabei auf die Abbruchtasten.
     *
     * @return {@code true}, wenn der Benutzer die Ansicht beenden will
     * @throws IOException wenn das Terminal nicht mehr lesbar ist
     */
    private boolean awaitExit() throws IOException {
        long deadline = System.nanoTime() + intervalMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            // Negative Werte bedeuten "noch nichts gelesen" (Timeout oder EOF).
            int read = terminal.reader().read(POLL_MILLIS);
            if (read == INTERRUPT || read == ESCAPE || read == 'q' || read == 'Q') {
                return true;
            }
        }
        return false;
    }

    private void draw() {
        String text;
        try {
            text = Dashboard.render(source.get());
        } catch (RuntimeException e) {
            text = Theme.fail("Fehler: " + messageOf(e));
        }
        synchronized (output) {
            PrintWriter writer = terminal.writer();
            // Überschreiben statt löschen: Der Bildschirm bleibt zwischen zwei
            // Aufbauten gefüllt und flackert nicht bei jeder Aktualisierung.
            writer.print(HOME);
            writeLines(writer, text);
            writer.print(NEWLINE);
            writeLines(writer, Theme.dim(footer()));
            writer.print(ERASE_DOWN);
            writer.flush();
            terminal.flush();
        }
    }

    /**
     * Schreibt Zeilen mit Wagenrücklauf und leert den Rest der jeweiligen Zeile.
     *
     * @param writer Ziel
     * @param text   mehrzeiliger Text
     */
    private static void writeLines(PrintWriter writer, String text) {
        for (String line : text.split("\\R", -1)) {
            writer.print(line);
            writer.print(ERASE_LINE);
            writer.print(NEWLINE);
        }
    }

    private String footer() {
        return "q oder Strg+C beendet  ·  Aktualisierung alle " + (intervalMillis / 1_000) + " s";
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
