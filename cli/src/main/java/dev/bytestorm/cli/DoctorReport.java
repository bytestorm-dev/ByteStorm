package dev.bytestorm.cli;

import java.util.List;

/**
 * Zeichnet die Ausgabe von {@code doctor}: eine Zeile je Prüfbereich, davor eine
 * Zusammenfassung. Die Farben kommen aus {@link Theme} und verschwinden an einer
 * Pipe, damit die Ausgabe dort unverändert lesbar bleibt.
 */
public final class DoctorReport {

    private static final String NL = System.lineSeparator();

    private DoctorReport() {
    }

    /**
     * @param findings Ergebnisse der Prüfungen
     * @return Textblock für das Terminal
     */
    public static String render(List<Diagnostics.Finding> findings) {
        List<Diagnostics.Finding> results = findings == null ? List.of() : findings;
        int errors = 0;
        int warnings = 0;
        int width = 0;
        for (Diagnostics.Finding finding : results) {
            if (finding.severity() == Diagnostics.Severity.ERROR) {
                errors++;
            } else if (finding.severity() == Diagnostics.Severity.WARNING) {
                warnings++;
            }
            width = Math.max(width, finding.area().length());
        }

        StringBuilder out = new StringBuilder();
        out.append(Theme.bold("ByteStorm")).append(Theme.dim("  ·  Diagnose"));
        out.append(NL).append(summary(results.size(), warnings, errors));
        for (Diagnostics.Finding finding : results) {
            out.append(NL).append(icon(finding.severity())).append(" ")
                    .append(Theme.bold(pad(finding.area(), width))).append("  ")
                    .append(finding.detail());
        }
        return out.toString();
    }

    private static String summary(int total, int warnings, int errors) {
        if (errors == 0 && warnings == 0) {
            return Theme.green(total + (total == 1 ? " Prüfung in Ordnung" : " Prüfungen in Ordnung"));
        }
        String counts = total + (total == 1 ? " Prüfung" : " Prüfungen")
                + "  ·  " + warnings + (warnings == 1 ? " Warnung" : " Warnungen")
                + "  ·  " + errors + (errors == 1 ? " Fehler" : " Fehler");
        return errors > 0 ? Theme.red(counts) : Theme.yellow(counts);
    }

    private static String icon(Diagnostics.Severity severity) {
        return switch (severity) {
            case OK -> Theme.green(Theme.tick());
            case WARNING -> Theme.yellow("!");
            case ERROR -> Theme.red(Theme.cross());
        };
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }
}
