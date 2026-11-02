package dev.bytestorm.cli;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Eine Zeile im Konsolen-Format {@code 16:42:01|INFO  Text}.
 *
 * <p>Konsole, Service-Screen und Dashboard zeigen damit dieselbe Form: Uhrzeit,
 * senkrechter Strich, Level. Das Level wird auf fünf Zeichen aufgefüllt und wie
 * im Konsolen-Appender gefärbt (ERROR fett rot, WARN gelb, INFO grün,
 * DEBUG/TRACE gedämpft), damit die Nachrichten untereinander stehen. Der
 * Logger-Name bleibt draußen; er steht nur in {@code logs/cloud.log}.
 *
 * <p>Der Service-Screen bekommt seine Zeilen aus dem Service-Log
 * ({@code [12:00:00] [OUT] Text}), das Dashboard aus {@code logs/cloud.log}.
 * Beide Formate werden hier in die Konsolenform übersetzt, damit dieselben
 * Zeilen überall gleich aussehen.
 */
public final class ConsoleLine {

    /** Uhrzeit im Konsolen-Format. */
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Zeile aus dem Service-Log: {@code [12:00:00] [OUT] Text}. */
    private static final Pattern SERVICE = Pattern.compile(
            "^\\[(\\d{2}:\\d{2}:\\d{2})] \\[(OUT|ERR)] ?(.*)$", Pattern.DOTALL);

    /** Zeile aus {@code logs/cloud.log}: {@code 2026-11-02 12:00:00.123 [main] INFO  x.y.Z - Text}. */
    private static final Pattern LOG_FILE = Pattern.compile(
            "^(?:\\d{4}-\\d{2}-\\d{2} )?(\\d{2}:\\d{2}:\\d{2})\\.\\d{3} \\[[^\\]]*] (\\w+)\\s+\\S+ - (.*)$",
            Pattern.DOTALL);

    private ConsoleLine() {
    }

    /**
     * @param time    Uhrzeit
     * @param level   Level, z. B. {@code INFO}
     * @param message Meldung, darf mehrzeilig sein
     * @return die Zeile für das Terminal
     */
    public static String format(LocalTime time, String level, String message) {
        return format(time.format(TIME), level, message);
    }

    /**
     * @param time    bereits formatierte Uhrzeit
     * @param level   Level, z. B. {@code WARN}
     * @param message Meldung, darf mehrzeilig sein
     * @return die Zeile für das Terminal
     */
    public static String format(String time, String level, String message) {
        String name = level == null || level.isBlank() ? "INFO" : level.toUpperCase(Locale.ROOT);
        String text = message == null ? "" : message;
        return Theme.dim(time) + Theme.dim("|") + level(name) + " " + text;
    }

    /**
     * Übersetzt eine Zeile aus dem Service-Log in die Konsolenform. Zeilen ohne
     * Zeitstempel bleiben unverändert, weil sie oft mitten in einer Ausgabe
     * stehen (etwa eine Fortschrittsanzeige) und jedes Umschreiben nur störte.
     *
     * <p>{@code OUT} wird zu {@code INFO}, {@code ERR} zu {@code ERROR}: Der
     * Kanal eines Services ist das, was im Log dem Level entspricht.
     *
     * @param rawLine gelesene Zeile
     * @return die Zeile im Konsolenformat
     */
    public static String service(String rawLine) {
        if (rawLine == null) {
            return "";
        }
        Matcher matcher = SERVICE.matcher(rawLine);
        if (!matcher.matches()) {
            return rawLine;
        }
        String level = "ERR".equals(matcher.group(2)) ? "ERROR" : "INFO";
        return format(matcher.group(1), level, matcher.group(3));
    }

    /**
     * Übersetzt eine Zeile aus {@code logs/cloud.log} in die Konsolenform.
     * Fortsetzungen einer mehrzeiligen Meldung haben keinen Zeitstempel; sie
     * bleiben gedämpft stehen, damit ein Stacktrace nicht verschwindet.
     *
     * @param rawLine gelesene Zeile
     * @return die Zeile im Konsolenformat, {@code null} für Leerzeilen
     */
    public static String cloudLog(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return null;
        }
        Matcher matcher = LOG_FILE.matcher(rawLine);
        if (!matcher.matches()) {
            return Theme.dim(rawLine);
        }
        return format(matcher.group(1), matcher.group(2), matcher.group(3));
    }

    /**
     * @param level Levelname
     * @return der gefärbte Name auf fünf Zeichen aufgefüllt
     */
    static String level(String level) {
        String padded = level.length() >= 5 ? level : level + " ".repeat(5 - level.length());
        return switch (level) {
            case "ERROR" -> Theme.bold(Theme.red(padded));
            case "WARN" -> Theme.yellow(padded);
            case "DEBUG", "TRACE" -> Theme.dim(padded);
            default -> Theme.green(padded);
        };
    }
}
