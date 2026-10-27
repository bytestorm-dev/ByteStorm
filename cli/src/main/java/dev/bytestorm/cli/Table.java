package dev.bytestorm.cli;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ausgerichtete Tabelle für die CLI-Ausgaben (§17).
 *
 * <p>Die Kommandos bauten ihre Listen bisher mit Tabulatoren; je nach Terminal
 * sprang das Ergebnis. Diese Tabelle rechnet die Spaltenbreite aus dem sichtbaren
 * Text aus — Farbcodes zählen dabei nicht mit ({@link Theme#stripAnsi}) — und
 * füllt mit Leerzeichen auf. Zahlen stehen rechts, sofern eine Spalte
 * ausschließlich aus Zahlen besteht.
 *
 * <p>Ein fähiges Terminal bekommt eine durchgezogene Trennlinie
 * ({@code ─────}), ein Dumb-Terminal, eine Pipe oder ein Test eine aus
 * {@code -}. Fett gesetzt wird die Kopfzeile nur, wenn {@link Theme} Farben
 * erlaubt.
 *
 * <p>Beispiel:
 * <pre>
 * Table.of("Name", "Gruppe", "Status")
 *         .row("Lobby-1", "Lobby", Theme.serviceState(state))
 *         .render();
 * </pre>
 */
public final class Table {

    private static final String SEPARATOR = "  ";

    private final List<String> headers;
    private final List<List<String>> rows = new ArrayList<>();
    private final Set<Integer> rightAligned = new LinkedHashSet<>();

    private Table(String... headers) {
        Objects.requireNonNull(headers, "headers");
        if (headers.length == 0) {
            throw new IllegalArgumentException("Eine Tabelle braucht mindestens eine Spalte");
        }
        this.headers = List.of(headers);
    }

    /**
     * @param headers Spaltenüberschriften
     * @return neue, leere Tabelle
     */
    public static Table of(String... headers) {
        return new Table(headers);
    }

    /**
     * Erzwingt die Ausrichtung von Spalten, die nicht als Zahl erkannt werden.
     *
     * @param columns Spaltenindizes, 0-basiert
     * @return dieselbe Tabelle
     */
    public Table right(int... columns) {
        for (int column : columns) {
            rightAligned.add(column);
        }
        return this;
    }

    /**
     * Fügt eine Zeile hinzu.
     *
     * @param cells Werte; farbige Werte sind erlaubt
     * @return dieselbe Tabelle
     * @throws IllegalArgumentException wenn die Spaltenzahl nicht passt
     */
    public Table row(String... cells) {
        if (cells.length != headers.size()) {
            throw new IllegalArgumentException("Zeile hat " + cells.length + " Spalten, erwartet "
                    + headers.size() + ": " + String.join(", ", cells));
        }
        rows.add(List.of(cells));
        return this;
    }

    /**
     * @return {@code true}, wenn außer der Kopfzeile nichts da ist
     */
    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * @return die Tabelle als Text, Zeilen mit {@link System#lineSeparator()} getrennt
     */
    public String render() {
        int[] widths = widths();
        StringBuilder out = new StringBuilder();
        appendRow(out, headers, widths, true);

        String rule = Theme.unicode() ? "─" : "-";
        StringBuilder separator = new StringBuilder();
        for (int column = 0; column < widths.length; column++) {
            if (column > 0) {
                separator.append(SEPARATOR);
            }
            separator.append(rule.repeat(widths[column]));
        }
        out.append(System.lineSeparator()).append(Theme.dim(separator.toString()));

        for (List<String> row : rows) {
            out.append(System.lineSeparator());
            appendRow(out, row, widths, false);
        }
        return out.toString();
    }

    private void appendRow(StringBuilder out, List<String> cells, int[] widths, boolean header) {
        for (int column = 0; column < cells.size(); column++) {
            if (column > 0) {
                out.append(SEPARATOR);
            }
            String text = cells.get(column) == null ? "" : cells.get(column);
            String padded = pad(text, widths[column], isRight(column, header));
            out.append(header ? Theme.bold(padded) : padded);
        }
    }

    private boolean isRight(int column, boolean header) {
        if (rightAligned.contains(column)) {
            return true;
        }
        if (header) {
            return false;
        }
        for (List<String> row : rows) {
            String value = row.get(column);
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!Theme.stripAnsi(value).trim().matches("\\d+")) {
                return false;
            }
        }
        return !rows.isEmpty();
    }

    private String pad(String text, int width, boolean right) {
        int visible = Theme.stripAnsi(text).length();
        String fill = " ".repeat(Math.max(0, width - visible));
        return right ? fill + text : text + fill;
    }

    private int[] widths() {
        int[] widths = new int[headers.size()];
        for (int column = 0; column < headers.size(); column++) {
            widths[column] = visibleLength(headers.get(column));
        }
        for (List<String> row : rows) {
            for (int column = 0; column < row.size(); column++) {
                widths[column] = Math.max(widths[column], visibleLength(row.get(column)));
            }
        }
        return widths;
    }

    private static int visibleLength(String text) {
        return Theme.stripAnsi(text == null ? "" : text).length();
    }
}
