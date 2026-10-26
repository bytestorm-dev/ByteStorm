package dev.bytestorm.cli;

import dev.bytestorm.controller.template.TemplateManager;
import dev.bytestorm.controller.template.TemplateManager.SyncResult;
import dev.bytestorm.controller.template.TemplateManager.TemplateEntry;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Template-Kommandos der CLI (§10, §17).
 *
 * <p>Wie die übrigen Dispatcher ist die Klasse unabhängig von JLine: Sie bekommt
 * die Tokens einer Zeile und liefert Text zurück. Die Arbeit macht der
 * {@link TemplateManager}, der die Ordner unter {@code templates/} verwaltet.
 */
public final class TemplateCommandLine {

    /** Hilfe für die Template-Kommandos. */
    public static final String USAGE = """
            templates list                       alle Templates mit Größe und Alter
            templates create <name>              leeren Template-Ordner anlegen
            templates delete <name>              Template samt Inhalt löschen
            templates copy <quelle> <ziel>       Template unter neuem Namen kopieren
            templates sync <service> <template>  Service-Ordner ins Template spiegeln
            templates help                       diese Hilfe

            `template` ist ein Alias für `templates`.

            Die festen Ebenen ALL, PROXY, SERVER und FALLBACK legt die Cloud an; sie
            gelten für jeden Service, alle Proxy- bzw. Server-Gruppen und zusätzlich
            für Fallback-Gruppen. Eigene Templates bindet `group edit <name> --template
            <name>` an eine Gruppe. Beim `sync` bleiben Laufzeitdaten außen vor: logs/,
            crash-reports/, Weltordner sowie Plattform- und Bridge-Jars.
            """;

    private static final String NL = System.lineSeparator();

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TemplateManager templates;

    /**
     * @param templates Template-Verwaltung
     */
    public TemplateCommandLine(TemplateManager templates) {
        this.templates = Objects.requireNonNull(templates, "templates");
    }

    /**
     * Führt ein Template-Kommando aus.
     *
     * @param tokens vollständige Tokens, z. B. {@code [templates, copy, Lobby, Lobby-Test]}
     * @return Ausgabe für das Terminal
     */
    public String execute(String[] tokens) {
        if (tokens.length < 2) {
            return USAGE.stripTrailing();
        }
        String command = tokens[1].toLowerCase(Locale.ROOT);
        try {
            return switch (command) {
                case "help" -> USAGE.stripTrailing();
                case "list" -> list();
                case "create" -> create(tokens);
                case "delete" -> delete(tokens);
                case "copy" -> copy(tokens);
                case "sync" -> sync(tokens);
                default -> "Unbekanntes Template-Kommando: " + command + NL + USAGE.stripTrailing();
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "Fehler: " + e.getMessage();
        } catch (RuntimeException e) {
            return "Fehler: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * @return die Namen aller Templates, alphabetisch; leer wenn templates/ nicht lesbar ist
     */
    public List<String> names() {
        try {
            return templates.list().stream().map(TemplateEntry::name).toList();
        } catch (RuntimeException e) {
            // Die Tab-Vervollständigung darf an einem Fehler nicht scheitern.
            return List.of();
        }
    }

    private String list() {
        List<TemplateEntry> entries = templates.list();
        if (entries.isEmpty()) {
            return "Keine Templates unter templates/ gefunden.";
        }
        StringJoiner out = new StringJoiner(NL);
        out.add("Name\tDateien\tGröße\tGeändert\tEbene");
        for (TemplateEntry entry : entries) {
            out.add(entry.name() + "\t" + entry.files() + "\t" + size(entry.bytes())
                    + "\t" + timestamp(entry.lastModified())
                    + "\t" + (entry.fixed() ? "fest" : "eigenes"));
        }
        return out.toString();
    }

    private String create(String[] tokens) {
        if (tokens.length < 3) {
            return "Templatename fehlt (templates create <name>)";
        }
        TemplateEntry created = templates.create(tokens[2]);
        return "Template '" + created.name() + "' angelegt (" + fixedHint(created) + "). "
                + "Inhalt aus templates/" + created.name() + "/ ablegen oder `templates sync` nutzen.";
    }

    private String delete(String[] tokens) {
        if (tokens.length < 3) {
            return "Templatename fehlt (templates delete <name>)";
        }
        String name = tokens[2];
        boolean fixed = templates.delete(name);
        return "Template '" + name + "' gelöscht."
                + (fixed ? " Feste Ebene: der nächste Start legt die Standard-Dateien wieder an, "
                + "Gruppen mit diesem Template laufen bis dahin ohne diese Ebene." : "");
    }

    private String copy(String[] tokens) {
        if (tokens.length < 4) {
            return "Quelle und Ziel fehlen (templates copy <quelle> <ziel>)";
        }
        TemplateEntry copied = templates.copy(tokens[2], tokens[3]);
        return "Template '" + tokens[2] + "' nach '" + copied.name() + "' kopiert ("
                + copied.files() + " Dateien, " + size(copied.bytes()) + ").";
    }

    private String sync(String[] tokens) {
        if (tokens.length < 4) {
            return "Service und Template fehlen (templates sync <service> <template>)";
        }
        SyncResult result = templates.sync(tokens[2], tokens[3]);
        return "Ordner von '" + tokens[2] + "' nach Template '" + result.template().name()
                + "' gespiegelt: " + result.files() + " Dateien, " + size(result.template().bytes())
                + ". Laufende Services nutzen die Änderung erst nach `service reapply <name>`.";
    }

    private static String fixedHint(TemplateEntry entry) {
        return entry.fixed() ? "feste Ebene" : "eigenes Template";
    }

    private static String timestamp(Instant instant) {
        if (instant.equals(Instant.EPOCH)) {
            return "-";
        }
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(TIMESTAMP);
    }

    /**
     * @param bytes Größe in Bytes
     * @return lesbare Größe, z. B. {@code 3,4 KiB}
     */
    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes / 1024.0;
        for (String unit : List.of("KiB", "MiB", "GiB")) {
            if (value < 1024 || unit.equals("GiB")) {
                return String.format(Locale.GERMAN, "%.1f %s", value, unit);
            }
            value /= 1024.0;
        }
        return String.format(Locale.GERMAN, "%.1f GiB", value);
    }
}
