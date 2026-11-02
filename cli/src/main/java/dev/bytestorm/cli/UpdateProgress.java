package dev.bytestorm.cli;

import dev.bytestorm.core.update.Updater;

import java.io.PrintStream;
import java.util.Locale;
import java.util.Objects;

/**
 * Fortschrittsbalken für den Download eines Release-Artefakts.
 *
 * <p>An einem Terminal wird eine einzige Zeile überschrieben ({@code \r}), damit
 * der Balken läuft statt zu scrollen. Umgeleitete Ausgabe — etwa ein Logfile —
 * bekommt nur den Endstand, denn hundert Zwischenzeilen machen ein Protokoll
 * unlesbar. Der Balken selbst kommt aus {@link Theme}, bleibt also auch ohne
 * Farben und ohne Unicode-Zeichen ablesbar.
 *
 * <p>Nennt der Server keine Gesamtgröße, gibt es keinen Anteil; dann zeigt die
 * Zeile nur die gelesenen Bytes.
 */
public final class UpdateProgress implements Updater.ProgressListener {

    /** Breite des Balkens in Zeichen. */
    static final int WIDTH = 28;

    /** Kürzerer Abstand flackert nur, ohne mehr zu sagen. */
    private static final long REPORT_INTERVAL_MILLIS = 150;

    private static final String[] UNITS = {"KiB", "MiB", "GiB", "TiB"};

    private final PrintStream out;
    private final boolean interactive;

    private long lastReport;
    private long lastRead = -1;

    /**
     * @param out         Ziel der Ausgabe
     * @param interactive {@code true} für eine überschriebene Zeile am Terminal
     */
    public UpdateProgress(PrintStream out, boolean interactive) {
        this.out = Objects.requireNonNull(out, "out");
        this.interactive = interactive;
    }

    /**
     * @return Anzeige für die aktuelle Konsole; ohne Terminal nur der Endstand
     */
    public static UpdateProgress console() {
        return new UpdateProgress(System.out, System.console() != null);
    }

    @Override
    public void onProgress(long read, long total) {
        boolean done = total > 0 && read >= total;
        long now = System.currentTimeMillis();
        if (!done && lastRead >= 0 && now - lastReport < REPORT_INTERVAL_MILLIS) {
            return;
        }
        lastReport = now;
        lastRead = read;

        if (!interactive && !done) {
            return;
        }
        String line = line(read, total);
        if (interactive) {
            // Leerzeichen am Ende decken die Reste einer längeren vorherigen Zeile ab.
            out.print("\r" + line + "   ");
            if (done) {
                out.println();
            }
        } else {
            out.println(line);
        }
        out.flush();
    }

    /**
     * @param read  gelesene Bytes
     * @param total Gesamtgröße oder {@code -1}
     * @return die Zeile mit Balken, Prozent und Größe
     */
    String line(long read, long total) {
        if (total <= 0) {
            return Theme.dim("Download") + " " + size(read);
        }
        double ratio = Math.min(1, (double) read / total);
        return Theme.bar(ratio, WIDTH)
                + " " + Theme.bold(String.format(Locale.ROOT, "%3d %%", Math.round(ratio * 100)))
                + " " + Theme.dim(size(read) + " / " + size(total));
    }

    /**
     * @param bytes Größe in Bytes
     * @return lesbare Größe, z. B. {@code 103,8 MiB}
     */
    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.GERMAN, "%.1f %s", value, UNITS[unit]);
    }
}
