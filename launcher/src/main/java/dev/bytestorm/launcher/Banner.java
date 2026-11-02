package dev.bytestorm.launcher;

import dev.bytestorm.cli.Theme;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Startbanner: drei Sekunden, von links nach rechts aufgedeckt, dann weg.
 *
 * <p>Die Vorlage steht in {@code assets/banner.txt} im Cloud-Verzeichnis. Enthält
 * die Datei mehrere Blöcke (Alternativen für verschiedene Zeichensätze), zählt
 * der erste; Zeilen wie {@code (loaded)} sind Notizen aus dem Zeichentest und
 * werden übersprungen. Fehlt die Datei, startet die Cloud ohne Vorspann.
 *
 * <p>Gezeichnet wird nur an einem echten Terminal: An einer Pipe oder im CI
 * würde eine Animation den Mitschnitt mit Steuerzeichen füllen. Nach dem
 * Aufdecken hält das Bild kurz und wird dann gelöscht, damit das Start-Log wie
 * gewohnt oben anfängt. Das Banner steht mittig in der Terminalbreite; ist die
 * Breite nicht ermittelbar, bleibt es links. Die Farben je Zeile laufen von
 * Cyan über Grün nach Magenta; ohne Farben (etwa {@code --no-color}) bleibt das
 * Banner reiner Text.
 */
public final class Banner {

    /** Pfad der Vorlage, relativ zum Cloud-Verzeichnis. */
    public static final String FILE = "assets/banner.txt";

    /** Zeit, die das Banner insgesamt steht. */
    public static final long SHOW_MILLIS = 3_000;

    /** Davon entfallen auf das Aufdecken; der Rest ist Halten. */
    static final long REVEAL_MILLIS = 2_400;

    /** Abstand zweier Bilder der Aufdeckphase. */
    static final long FRAME_MILLIS = 80;

    private static final String ERASE_LINE = "\u001B[2K";
    private static final String UP = "\u001B[%dA";

    private Banner() {
    }

    /**
     * Zeigt das Banner, wenn ein Terminal vorhanden ist und die Vorlage existiert.
     *
     * @param cloudDirectory Cloud-Verzeichnis mit {@code assets/banner.txt}
     */
    public static void show(Path cloudDirectory) {
        if (System.console() == null) {
            return;
        }
        List<String> lines = read(cloudDirectory);
        if (lines.isEmpty()) {
            return;
        }
        show(lines, System.out, terminalWidth());
    }

    /**
     * @param lines Bannerzeilen
     * @param out   Ziel, üblicherweise {@code System.out}
     */
    static void show(List<String> lines, PrintStream out) {
        show(lines, out, 0);
    }

    /**
     * Spielt die Animation ab. Die Methode kehrt nach {@link #SHOW_MILLIS} zurück
     * oder sofort, wenn der Thread unterbrochen wird.
     *
     * @param lines Bannerzeilen
     * @param out   Ziel, üblicherweise {@code System.out}
     * @param width Terminalbreite für die Mitte, {@code 0} wenn unbekannt
     */
    static void show(List<String> lines, PrintStream out, int width) {
        if (lines.isEmpty()) {
            return;
        }
        // Farben hängen wie in der Konsole an der Umgebung, nicht am Terminaltyp:
        // das Banner läuft, bevor die Konsole ihr Terminal öffnet.
        Theme.apply(System.getenv("TERM"));

        int height = lines.size();
        int frames = (int) Math.max(1, REVEAL_MILLIS / FRAME_MILLIS);
        try {
            for (int frame = 0; frame <= frames; frame++) {
                draw(out, center(reveal(lines, (double) frame / frames), width), height, frame == 0);
                Thread.sleep(FRAME_MILLIS);
            }
            Thread.sleep(Math.max(0, SHOW_MILLIS - REVEAL_MILLIS - FRAME_MILLIS));
            clear(out, height);
        } catch (InterruptedException e) {
            // Strg+C während des Vorspanns: Bild wegräumen und den Start überlassen.
            Thread.currentThread().interrupt();
            clear(out, height);
        }
    }

    /**
     * Liest die Vorlage.
     *
     * @param cloudDirectory Cloud-Verzeichnis
     * @return die Zeilen des ersten Blocks, leer wenn keine Vorlage gefunden wird
     */
    static List<String> read(Path cloudDirectory) {
        if (cloudDirectory == null) {
            return List.of();
        }
        List<String> lines = load(cloudDirectory);
        List<String> block = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                if (!block.isEmpty()) {
                    // Ab hier folgen Alternativen, nicht mehr das Banner.
                    break;
                }
                continue;
            }
            block.add(line);
        }
        return List.copyOf(block);
    }

    /**
     * @param cloudDirectory Cloud-Verzeichnis
     * @return alle Zeilen ohne Notizen der Zeichentests, leer wenn keine Datei lesbar ist
     */
    private static List<String> load(Path cloudDirectory) {
        for (Path candidate : List.of(cloudDirectory.resolve(FILE), cloudDirectory.resolve("banner.txt"))) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                List<String> kept = new ArrayList<>();
                for (String line : Files.readAllLines(candidate, StandardCharsets.UTF_8)) {
                    String trimmed = line.strip();
                    if (trimmed.equals("(loaded)") || trimmed.equals("(unloaded)")) {
                        continue;
                    }
                    kept.add(line.stripTrailing());
                }
                return kept;
            } catch (IOException e) {
                // Unlesbare Vorlage: lieber ohne Banner starten als den Start zu verweigern.
                return List.of();
            }
        }
        return List.of();
    }

    /**
     * Deckt einen Anteil des Banners auf. Noch unsichtbare Zeichen sind
     * Leerzeichen, damit die Zeilen nicht springen, während sie wachsen.
     *
     * @param lines Bannerzeilen
     * @param ratio Anteil zwischen 0 und 1
     * @return die Zeilen des aktuellen Bildes
     */
    static List<String> reveal(List<String> lines, double ratio) {
        int total = 0;
        for (String line : lines) {
            total += line.length();
        }
        int visible = (int) Math.round(Math.max(0, Math.min(1, ratio)) * total);
        List<String> frame = new ArrayList<>(lines.size());
        int left = visible;
        for (String line : lines) {
            int shown = Math.max(0, Math.min(line.length(), left));
            frame.add(line.substring(0, shown) + " ".repeat(line.length() - shown));
            left -= shown;
        }
        return List.copyOf(frame);
    }

    /**
     * @return die Breite des Terminals in Zeichen, {@code 0} wenn sie sich nicht ermitteln lässt
     */
    private static int terminalWidth() {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            int width = terminal.getWidth();
            return width > 0 ? width : 0;
        } catch (IOException | RuntimeException | LinkageError e) {
            // Ohne Größe startet die Cloud ohne Zentrierung, nicht ohne Banner.
            return 0;
        }
    }

    /**
     * Rückt jede Zeile so ein, dass das Banner mittig steht. Ist das Banner
     * breiter als das Terminal, bleibt es links stehen, statt über den Rand zu
     * laufen.
     *
     * @param lines Bannerzeilen
     * @param width Terminalbreite, {@code 0} für linksbündig
     * @return die eingerückten Zeilen
     */
    static List<String> center(List<String> lines, int width) {
        if (width <= 0 || lines.isEmpty()) {
            return lines;
        }
        List<String> centered = new ArrayList<>(lines.size());
        for (String line : lines) {
            int pad = Math.max(0, (width - line.length()) / 2);
            centered.add(" ".repeat(pad) + line);
        }
        return List.copyOf(centered);
    }

    private static void draw(PrintStream out, List<String> frame, int height, boolean first) {
        if (!first) {
            // Über das vorige Bild zurück an den Anfang, dann Zeile für Zeile neu.
            out.print(UP.formatted(height));
        }
        for (int index = 0; index < frame.size(); index++) {
            out.print('\r');
            out.print(ERASE_LINE);
            out.print(paint(frame.get(index), index, frame.size()));
            out.print('\n');
        }
        out.flush();
    }

    private static void clear(PrintStream out, int height) {
        out.print(UP.formatted(height));
        for (int index = 0; index < height; index++) {
            out.print('\r');
            out.print(ERASE_LINE);
            out.print('\n');
        }
        out.print(UP.formatted(height));
        out.flush();
    }

    /**
     * Färbt eine Bannerzeile nach ihrer Höhe: oben Cyan, in der Mitte hellgrün,
     * unten Magenta.
     *
     * @param line  Zeile
     * @param index Position im Banner
     * @param size  Anzahl der Zeilen
     * @return die gefärbte Zeile
     */
    private static String paint(String line, int index, int size) {
        if (!Theme.enabled() || line.isBlank()) {
            return line;
        }
        double position = size <= 1 ? 0 : (double) index / (size - 1);
        if (position < 0.4) {
            return Theme.bold(Theme.cyan(line));
        }
        return Theme.bold(position < 0.75 ? Theme.accent(line) : Theme.magenta(line));
    }
}
