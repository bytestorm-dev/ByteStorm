package dev.bytestorm.launcher.download;

import java.io.PrintStream;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Fortschrittsanzeige für Downloads auf der Konsole.
 *
 * <p>An einem Terminal wird die Zeile an Ort und Stelle aktualisiert
 * ({@code \r}); umgeleitete Ausgabe bekommt eine Zeile je Datei, damit Logs
 * lesbar bleiben. Der Empfänger ist synchronisiert, weil die Downloads parallel
 * fertig werden.
 */
public final class DownloadProgressPrinter implements Consumer<DownloadStatus> {

    private final PrintStream out;
    private final boolean interactive;

    /**
     * @param out         Ziel, z. B. {@link System#out}
     * @param interactive {@code true} für eine aktualisierte Zeile am Terminal
     */
    public DownloadProgressPrinter(PrintStream out, boolean interactive) {
        this.out = Objects.requireNonNull(out, "out");
        this.interactive = interactive;
    }

    /**
     * @return Anzeige für die aktuelle Konsole; ohne Terminal zeilenweise Ausgabe
     */
    public static DownloadProgressPrinter console() {
        return new DownloadProgressPrinter(System.out, System.console() != null);
    }

    @Override
    public synchronized void accept(DownloadStatus status) {
        String line = "[" + status.completed() + "/" + status.total() + "] "
                + (status.downloaded() ? "geladen" : "vorhanden") + ": "
                + status.dependency().fileName();
        if (interactive) {
            out.print("\r" + line + "        ");
            if (status.finished()) {
                out.println();
            }
        } else {
            out.println(line);
        }
        out.flush();
    }
}
