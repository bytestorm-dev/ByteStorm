package dev.bytestorm.cli;

import dev.bytestorm.core.alert.Alert;
import dev.bytestorm.core.alert.AlertSeverity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Kommando {@code alerts}: zeigt die Alarme, die gerade gelten (§17, §20).
 *
 * <p>Die Liste kommt aus dem laufenden {@code AlertService} — dieselben Zustände,
 * die auch nach {@code logs/alerts.log} gehen. Sie ist damit eine Momentaufnahme:
 * Nach einem Neustart ist sie leer, der Verlauf steht in der Datei. Alarme werden
 * nach Stufe sortiert, kritische zuerst; innerhalb einer Stufe nach Quelle, damit
 * zwei Abfragen hintereinander dieselbe Reihenfolge zeigen.
 */
public final class AlertsCommandLine {

    /** Hilfe für {@code alerts help}. */
    public static final String USAGE = """
            alerts          aktive Alarme: Stufe, Quelle und nächster Schritt
            """;

    private static final String EMPTY_HINT =
            "Die Cloud prüft alle 30 Sekunden; der Verlauf steht in logs/alerts.log";

    private final Supplier<List<Alert>> active;

    /**
     * @param active liefert die gerade aktiven Alarme, z. B.
     *               {@code alertService::active}
     */
    public AlertsCommandLine(Supplier<List<Alert>> active) {
        this.active = Objects.requireNonNull(active, "active");
    }

    /**
     * @param tokens Kommandozeile, z. B. {@code alerts}
     * @return Ausgabe für das Terminal
     */
    public String execute(String[] tokens) {
        if (tokens.length > 1
                && (tokens[1].equals("help") || tokens[1].equals("-h") || tokens[1].equals("--help"))) {
            return Theme.help(USAGE.stripTrailing());
        }

        List<Alert> alerts;
        try {
            alerts = new ArrayList<>(active.get());
        } catch (RuntimeException e) {
            return Theme.fail("Fehler: " + messageOf(e));
        }
        alerts.sort(Comparator
                .comparingInt((Alert alert) -> alert.severity() == AlertSeverity.CRITICAL ? 0 : 1)
                .thenComparing(Alert::source, String.CASE_INSENSITIVE_ORDER));

        String nl = System.lineSeparator();
        if (alerts.isEmpty()) {
            return Theme.ok("Keine aktiven Alarme") + nl + Theme.dim(EMPTY_HINT);
        }

        long critical = alerts.stream()
                .filter(alert -> alert.severity() == AlertSeverity.CRITICAL)
                .count();
        long warnings = alerts.size() - critical;

        StringBuilder out = new StringBuilder(512);
        out.append(Theme.section("ByteStorm  ·  Alarme")).append(nl)
                .append(alerts.size()).append(" aktiv  ·  ").append(critical).append(" kritisch  ·  ")
                .append(plural(warnings, "Warnung", "Warnungen")).append(nl)
                .append(nl);
        out.append(render(alerts));
        return out.toString();
    }

    private static String render(List<Alert> alerts) {
        Table table = Table.of("Stufe", "Quelle", "Meldung");
        for (Alert alert : alerts) {
            table.row(severity(alert.severity()), alert.source(), alert.message());
        }
        return table.render();
    }

    private static String severity(AlertSeverity severity) {
        return severity == AlertSeverity.CRITICAL ? Theme.red("kritisch") : Theme.yellow("Warnung");
    }

    private static String plural(long count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
