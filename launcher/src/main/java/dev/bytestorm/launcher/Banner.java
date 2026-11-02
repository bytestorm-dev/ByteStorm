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
 * Startbanner: Logo, Blitz, drei Sekunden.
 *
 * <p>Die Vorlage steht in {@code assets/banner.txt} im Cloud-Verzeichnis.
 * Enthält die Datei mehrere Blöcke (Alternativen für verschiedene Zeichensätze),
 * zählt der erste; Zeilen wie {@code (loaded)} sind Notizen aus dem Zeichentest
 * und werden übersprungen. Fehlt die Datei, startet die Cloud ohne Vorspann.
 *
 * <p>Das Logo erscheint sofort und steht still. Die Front ({@code █}) ist dunkles
 * Aqua, der Schatten dahinter ({@code ╔╗╚╝║═}) ein deutlich dunkleres Aqua —
 * dadurch wirkt die Schrift wie gesetzt statt flach. Rechts daneben schlägt ein
 * Blitz ein: er flackert weiß auf, beruhigt sich auf Gelb und bleibt bis zum Ende
 * stehen. Nach {@link #SHOW_MILLIS} wird der Block gelöscht, damit das Start-Log
 * wie gewohnt oben anfängt.
 *
 * <p>Gezeichnet wird nur an einem echten Terminal: An einer Pipe oder im CI
 * würde die Animation den Mitschnitt mit Steuerzeichen füllen. Ist die
 * Terminalbreite nicht ermittelbar, bleibt das Banner links.
 */
public final class Banner {

    /** Pfad der Vorlage, relativ zum Cloud-Verzeichnis. */
    public static final String FILE = "assets/banner.txt";

    /** Zeit, die das Banner insgesamt steht. */
    public static final long SHOW_MILLIS = 3_000;

    /** Abstand zweier Blitzbilder. */
    static final long FRAME_MILLIS = 110;

    /** Blitzfolge: {@code 1} ruhig gelb, {@code 2} grell weiß. */
    private static final int[] STRIKES = {1, 2, 1, 2, 1, 2, 1};

    /** Abstand zwischen Logo und Blitz. */
    private static final String GAP = "   ";

    /** Blitz, sechs Zeilen hoch wie das Logo, rechts davon. */
    private static final List<String> BOLT = List.of(
            "    ██",
            "   ██ ",
            "  ████",
            "    ██",
            "   ██ ",
            "  ██  ");

    /** Vordergrund: dunkles Aqua. */
    static final String FRONT_COLOR = "\u001B[38;5;37m";

    /** Schatten: dasselbe Aqua, zwei Stufen dunkler. */
    static final String SHADOW_COLOR = "\u001B[38;5;30m";

    private static final String BOLT_COLOR = "\u001B[38;5;226m";
    private static final String BOLT_FLASH = "\u001B[1;97m";
    private static final String RESET = "\u001B[0m";

    /** Zeichen, aus denen die Vorlage den Schatten setzt. */
    private static final String SHADOW_CHARS = "╔╗╚╝║═";

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
        // Erst das Logo allein — es steht ab dem ersten Bild still, nur der Blitz kommt dazu.
        draw(out, center(compose(lines, 0), width), height, true);
        try {
            for (int strike : STRIKES) {
                Thread.sleep(FRAME_MILLIS);
                draw(out, center(compose(lines, strike), width), height, false);
            }
            long spent = FRAME_MILLIS * (STRIKES.length + 1L);
            Thread.sleep(Math.max(0, SHOW_MILLIS - spent));
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
     * Setzt Logo und Blitz zu einem Block zusammen. Jede Zeile ist gleich breit,
     * damit die Mitte beim Zeichnen nicht springt; {@code bolt = 0} blendet den
     * Blitz aus und lässt nur das Logo stehen.
     *
     * @param logo Bannerzeilen der Vorlage
     * @param bolt {@code 0} ohne Blitz, {@code 1} ruhig, {@code 2} grell
     * @return die Zeilen des Bildes, bereits gefärbt
     */
    static List<String> compose(List<String> logo, int bolt) {
        if (logo.isEmpty()) {
            return List.of();
        }
        int logoWidth = 0;
        for (String line : logo) {
            logoWidth = Math.max(logoWidth, line.length());
        }
        int boltWidth = 0;
        for (String line : BOLT) {
            boltWidth = Math.max(boltWidth, line.length());
        }
        List<String> rows = new ArrayList<>(logo.size());
        for (int index = 0; index < logo.size(); index++) {
            String row = logo.get(index);
            String art = index < BOLT.size() ? BOLT.get(index) : " ".repeat(boltWidth);
            rows.add(paintLogo(row) + " ".repeat(logoWidth - row.length())
                    + GAP + paintBolt(art, bolt));
        }
        return List.copyOf(rows);
    }

    /**
     * Färbt eine Logozeile: Front dunkles Aqua, Schatten deutlich dunkler.
     * Leerzeichen bleiben ungefärbt, damit die Fläche zwischen den Zeichen ruhig
     * bleibt.
     *
     * @param line Logozeile
     * @return die gefärbte Zeile, ohne Farben unverändert
     */
    static String paintLogo(String line) {
        if (!Theme.enabled() || line.isBlank()) {
            return line;
        }
        StringBuilder out = new StringBuilder(line.length() + 16);
        String current = null;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            String color = character == '█' ? FRONT_COLOR
                    : SHADOW_CHARS.indexOf(character) >= 0 ? SHADOW_COLOR : null;
            if (color == null) {
                if (current != null) {
                    out.append(RESET);
                    current = null;
                }
            } else if (!color.equals(current)) {
                out.append(color);
                current = color;
            }
            out.append(character);
        }
        if (current != null) {
            out.append(RESET);
        }
        return out.toString();
    }

    /**
     * @param art   Blitzzeile
     * @param level {@code 0} aus, {@code 1} ruhig, {@code 2} grell
     * @return die gefärbte Blitzzeile
     */
    private static String paintBolt(String art, int level) {
        if (level <= 0) {
            return " ".repeat(art.length());
        }
        if (!Theme.enabled()) {
            return art;
        }
        return (level >= 2 ? BOLT_FLASH : BOLT_COLOR) + art + RESET;
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
     * Rückt jede Zeile so ein, dass das Banner mittig steht. Gemessen wird die
     * sichtbare Breite, nicht die Länge mit Farbcodes. Ist das Banner breiter als
     * das Terminal, bleibt es links stehen, statt über den Rand zu laufen.
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
            int visible = Theme.stripAnsi(line).length();
            centered.add(" ".repeat(Math.max(0, (width - visible) / 2)) + line);
        }
        return List.copyOf(centered);
    }

    private static void draw(PrintStream out, List<String> frame, int height, boolean first) {
        if (!first) {
            // Über das vorige Bild zurück an den Anfang, dann Zeile für Zeile neu.
            out.print("\u001B[" + height + "A");
        }
        for (String line : frame) {
            out.print('\r');
            out.print("\u001B[2K");
            out.print(line);
            out.print('\n');
        }
        out.flush();
    }

    private static void clear(PrintStream out, int height) {
        out.print("\u001B[" + height + "A");
        for (int index = 0; index < height; index++) {
            out.print('\r');
            out.print("\u001B[2K");
            out.print('\n');
        }
        out.print("\u001B[" + height + "A");
        out.flush();
    }
}
