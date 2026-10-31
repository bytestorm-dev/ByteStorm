package dev.bytestorm.cli;

import dev.bytestorm.proto.GroupState;
import dev.bytestorm.proto.ModuleState;
import dev.bytestorm.proto.ServiceState;

import java.util.regex.Pattern;

/**
 * Farben, Symbole und Zustandsfarben der CLI (§17).
 *
 * <p>Alle Kommandos liefern weiterhin reinen Text. Diese Klasse färbt ihn nur,
 * und zwar nur dort, wo es etwas zu sehen gibt: an einem echten Terminal. Die
 * Umschaltung passiert bewusst hier und nicht in jedem Kommando, damit Tests,
 * Pipes und Logfiles unverändert lesbaren Text bekommen.
 *
 * <p>Die Entscheidung fällt {@link #allowed}: Farben gibt es nur, wenn der
 * Terminal kein Dumb-Terminal ist, {@code NO_COLOR} nicht gesetzt ist, {@code TERM}
 * nicht {@code dumb} ist und {@code -Dbytestorm.color} nicht auf {@code never}
 * steht. {@code --no-color} des Launchers schaltet sie endgültig ab
 * ({@link #forcePlain}). Ist das Terminal nicht fähig, bleibt die Ausgabe reiner
 * Text — inklusive ASCII-Trennlinien statt Rahmenzeichen. Satzzeichen wie
 * {@code →} oder {@code ✔} sind UTF-8 und bleiben in beiden Fällen.
 */
public final class Theme {

    /** Terminal-Typ eines nicht-interaktiven Terminals (JLine). */
    public static final String DUMB = "dumb";

    /** Systemproperty, die Farben erzwingt oder verbietet ({@code always}/{@code never}). */
    public static final String COLOR_PROPERTY = "bytestorm.color";

    /** Umgebungsvariable der NO_COLOR-Konvention: jeder gesetzte Wert schaltet Farben ab. */
    public static final String NO_COLOR = "NO_COLOR";

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String CYAN = "\u001B[36m";
    private static final String GRAY = "\u001B[90m";
    private static final String BRIGHT_GREEN = "\u001B[92m";
    private static final String INVERSE_GREEN = "\u001B[42m\u001B[30m";

    private static final String TICK = "✔";
    private static final String CROSS = "✖";
    private static final String BULLET = "●";
    private static final String ARROW = "→";
    private static final String DASH = "—";

    private static volatile boolean enabled;
    private static volatile boolean plainForced;

    private Theme() {
    }

    /**
     * @return {@code true}, wenn Ausgaben gefärbt werden
     */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * @return {@code true}, wenn Rahmenzeichen statt ASCII verwendet werden dürfen
     */
    public static boolean unicode() {
        return enabled;
    }

    /**
     * Schaltet die Ausgabe um. Die Konsole ruft das nach der Terminal-Erkennung,
     * Tests können es gezielt setzen.
     *
     * @param value {@code true} färbt Ausgaben
     */
    public static void setEnabled(boolean value) {
        enabled = value && !plainForced;
    }

    /**
     * Verbietet Farben endgültig — die programmatische Entsprechung zu
     * {@code --no-color} bzw. {@code -Dbytestorm.color=never}. Die Erkennung
     * ändert daran nichts mehr, auch nicht in später geöffneten Terminals.
     *
     * @param force {@code true} schaltet Farben auch auf einem fähigen Terminal ab
     */
    public static void forcePlain(boolean force) {
        plainForced = force;
        if (force) {
            enabled = false;
        }
    }

    /**
     * Entscheidet, ob ein Terminal Farben bekommt. Reine Funktion, damit die
     * Regeln ohne Terminal prüfbar sind.
     *
     * @param terminalType Typ des Terminals, z. B. {@code xterm-256color} oder {@code dumb}
     * @param noColor      Wert von {@code NO_COLOR}, {@code null} wenn nicht gesetzt
     * @param term         Wert von {@code TERM}
     * @param color        Wert von {@code -Dbytestorm.color} ({@code always}/{@code never})
     * @return {@code true}, wenn gefärbt werden darf
     */
    public static boolean allowed(String terminalType, String noColor, String term, String color) {
        if (plainForced) {
            return false;
        }
        if (color != null && color.equalsIgnoreCase("never")) {
            return false;
        }
        if (color != null && color.equalsIgnoreCase("always")) {
            return true;
        }
        if (noColor != null && !noColor.isBlank()) {
            return false;
        }
        if (term != null && term.equalsIgnoreCase(DUMB)) {
            return false;
        }
        return terminalType != null && !terminalType.equalsIgnoreCase(DUMB);
    }

    /**
     * Wendet {@link #allowed} auf die Umgebung an.
     *
     * @param terminalType Typ des Terminals
     * @param noColor      Wert von {@code NO_COLOR}
     * @param term         Wert von {@code TERM}
     * @param color        Wert von {@code -Dbytestorm.color}
     */
    public static void apply(String terminalType, String noColor, String term, String color) {
        setEnabled(allowed(terminalType, noColor, term, color));
    }

    /**
     * Prüft die Umgebung des laufenden Prozesses.
     *
     * @param terminalType Typ des Terminals
     */
    public static void apply(String terminalType) {
        apply(terminalType, System.getenv(NO_COLOR), System.getenv("TERM"),
                System.getProperty(COLOR_PROPERTY));
    }

    /**
     * @param text Text
     * @param code ANSI-Code, z. B. {@link #RED}
     * @return gefärbter Text oder der Text unverändert
     */
    public static String style(String text, String code) {
        return enabled ? code + text + RESET : text;
    }

    /** @param text Text @return fett */
    public static String bold(String text) {
        return style(text, BOLD);
    }

    /** @param text Text @return gedämpft */
    public static String dim(String text) {
        return style(text, DIM);
    }

    /** @param text Text @return rot */
    public static String red(String text) {
        return style(text, RED);
    }

    /** @param text Text @return grün */
    public static String green(String text) {
        return style(text, GREEN);
    }

    /** @param text Text @return gelb */
    public static String yellow(String text) {
        return style(text, YELLOW);
    }

    /** @param text Text @return blau */
    public static String blue(String text) {
        return style(text, BLUE);
    }

    /** @param text Text @return magenta */
    public static String magenta(String text) {
        return style(text, MAGENTA);
    }

    /** @param text Text @return cyan */
    public static String cyan(String text) {
        return style(text, CYAN);
    }

    /** @param text Text @return grau */
    public static String gray(String text) {
        return style(text, GRAY);
    }

    /**
     * Hebt Rahmen, aktive Auswahl und bestätigte Antworten hervor: helles Grün.
     *
     * @param text Text
     * @return grün hervorgehobener Text oder der Text unverändert
     */
    public static String accent(String text) {
        return style(text, BRIGHT_GREEN);
    }

    /**
     * Fokuszeile eines Formulars: grüner Hintergrund mit dunkler Schrift, damit die
     * Auswahl auch ohne Farbwahrnehmung an der Position erkennbar bleibt.
     *
     * @param text Text
     * @return invertierter Text oder der Text unverändert
     */
    public static String highlight(String text) {
        return style(text, INVERSE_GREEN);
    }

    /**
     * @return Haken
     */
    public static String tick() {
        return TICK;
    }

    /**
     * @return Kreuz
     */
    public static String cross() {
        return CROSS;
    }

    /**
     * @return Punkt für Zustände
     */
    public static String bullet() {
        return BULLET;
    }

    /**
     * @return Pfeil für Übergänge
     */
    public static String arrow() {
        return ARROW;
    }

    /**
     * @return Gedankenstrich
     */
    public static String dash() {
        return DASH;
    }

    /**
     * Färbt eine Erfolgsmeldung. Der Text bleibt unverändert, damit Skripte ihn
     * weiterlesen können.
     *
     * @param message Meldung
     * @return grüne Meldung mit Haken, ungefärbt ohne Haken
     */
    public static String ok(String message) {
        return enabled ? green(TICK + " " + message) : message;
    }

    /**
     * Färbt eine Fehlermeldung.
     *
     * @param message Meldung, üblicherweise mit {@code Fehler: } davor
     * @return rote Meldung
     */
    public static String fail(String message) {
        return enabled ? red(CROSS + " " + message) : message;
    }

    /**
     * Färbt einen Hinweis.
     *
     * @param message Meldung
     * @return gelbe Meldung mit Ausrufezeichen
     */
    public static String warn(String message) {
        return enabled ? yellow("! " + message) : message;
    }

    /**
     * @param title Abschnittstitel
     * @return fett-cyaner Titel
     */
    public static String section(String title) {
        return enabled ? bold(cyan(title)) : title;
    }

    /**
     * @param name  Feldname ohne Doppelpunkt
     * @param value Wert
     * @return {@code Name: Wert} mit gedämpftem Namen
     */
    public static String field(String name, String value) {
        return (enabled ? dim(name + ":") : name + ":") + " " + value;
    }

    /**
     * Hebt in einer Hilfe den Kommandoanfang jeder Zeile hervor: Das erste Wort
     * hinter der Einrückung wird fett, Abschnittsüberschriften ganz.
     *
     * @param usage Hilfetext
     * @return der hervorgehobene Text
     */
    public static String help(String usage) {
        if (!enabled) {
            return usage;
        }
        String[] lines = usage.split("\n", -1);
        StringBuilder out = new StringBuilder(usage.length());
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                out.append('\n');
            }
            out.append(helpLine(lines[index]));
        }
        return out.toString();
    }

    private static String helpLine(String line) {
        if (line.isBlank()) {
            return line;
        }
        int start = 0;
        while (start < line.length() && line.charAt(start) == ' ') {
            start++;
        }
        int end = line.indexOf(' ', start);
        if (end < 0) {
            end = line.length();
        }
        return line.substring(0, start) + bold(line.substring(start, end)) + line.substring(end);
    }

    /**
     * @param state Zustand eines Services
     * @return gefärbter Zustandsname mit Punkt
     */
    public static String serviceState(ServiceState state) {
        String text = state.name();
        String color = switch (state) {
            case ONLINE -> GREEN;
            case STARTING, PREPARING -> YELLOW;
            case STOPPING, STOPPED -> GRAY;
            case CRASHED -> RED;
            case UNRECOGNIZED -> GRAY;
        };
        if (!enabled) {
            return text;
        }
        return color + BULLET + " " + text + RESET;
    }

    /**
     * @param state Zustand einer Gruppe
     * @return gefärbter Zustandsname mit Punkt
     */
    public static String groupState(GroupState state) {
        String text = state.name();
        String color = switch (state) {
            case HEALTHY -> GREEN;
            case DEGRADED -> RED;
            case MAINTENANCE -> YELLOW;
            case UNRECOGNIZED -> GRAY;
        };
        if (!enabled) {
            return text;
        }
        return color + BULLET + " " + text + RESET;
    }

    /**
     * @param state Zustand eines Moduls
     * @return gefärbter Zustandsname mit Punkt
     */
    public static String moduleState(ModuleState state) {
        String text = state.name();
        String color = switch (state) {
            case ENABLED, LOADED -> GREEN;
            case DISABLED -> GRAY;
            case FAILED -> RED;
            case UNRECOGNIZED -> GRAY;
        };
        if (!enabled) {
            return text;
        }
        return color + BULLET + " " + text + RESET;
    }

    /**
     * @param value {@code true} für „aktiv“
     * @return gefärbtes ja/nein
     */
    public static String yesNo(boolean value) {
        if (!enabled) {
            return value ? "ja" : "nein";
        }
        return value ? green(TICK + " ja") : dim("nein");
    }

    /**
     * @param value {@code true} für „aktiv“
     * @return gefärbter Wartungszustand
     */
    public static String maintenance(boolean value) {
        if (!enabled) {
            return value ? "aktiv" : "aus";
        }
        return value ? yellow("aktiv") : dim("aus");
    }

    /**
     * @param online laufende Services
     * @param total  bekannte Services
     * @return {@code 3/4} mit grüner Zahl, wenn alle laufen
     */
    public static String counts(int online, int total) {
        String text = online + "/" + total;
        if (!enabled) {
            return text;
        }
        return (online == total ? green(text) : yellow(text));
    }

    /**
     * Entfernt ANSI-Sequenzen. {@link Table} misst damit die sichtbare Breite.
     *
     * @param text Text, ggf. mit Farbcodes
     * @return Text ohne Farbcodes
     */
    public static String stripAnsi(String text) {
        return text == null ? "" : ANSI.matcher(text).replaceAll("");
    }
}
