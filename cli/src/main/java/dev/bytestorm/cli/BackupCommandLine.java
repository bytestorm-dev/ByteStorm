package dev.bytestorm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * {@code backup} — sichert MongoDB, {@code config/}, {@code templates/} und
 * {@code static/} in eine Zip-Datei (§15).
 *
 * <p>Die Kommandozeile kennt weder MongoDB noch das Layout: Sie reicht den
 * Auftrag an einen {@link Runner} weiter, den die Cloud beim Start verdrahtet.
 * Damit bleibt sie ohne Datenbank testbar.
 */
public final class BackupCommandLine {

    /** Hilfe für {@code backup}. */
    public static final String USAGE = """
            backup                   Cloud sichern (Mongo, config, templates, static)
            backup --out <datei>     an einen anderen Ort schreiben
            backup list              vorhandene Sicherungen anzeigen
            """;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    /**
     * Führt die Sicherung aus.
     */
    @FunctionalInterface
    public interface Runner {

        /**
         * @param target Zieldatei oder {@code null} für die Standardablage
         * @return Maße der angelegten Datei
         * @throws IOException wenn geschrieben oder gelesen werden kann nicht
         */
        Archive run(Path target) throws IOException;
    }

    /**
     * Ergebnis einer Sicherung.
     *
     * @param file        angelegte Datei
     * @param bytes       Größe in Bytes
     * @param collections Anzahl der gesicherten Collections
     * @param documents   Anzahl der Dokumente
     * @param files       Anzahl der Dateien aus dem Layout
     */
    public record Archive(Path file, long bytes, int collections, long documents, int files) {
    }

    private final Runner runner;
    private final Path directory;

    /**
     * @param runner    führt die Sicherung aus
     * @param directory Ablage für {@code backup list}, darf {@code null} sein
     */
    public BackupCommandLine(Runner runner, Path directory) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.directory = directory;
    }

    /**
     * @param args geteilte Eingabe, z. B. {@code backup --out /srv/x.zip}
     * @return Ausgabe für die Konsole
     */
    public String execute(String[] args) {
        if (args.length <= 1) {
            return create(null);
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> list();
            case "help", "--help", "-h" -> Theme.help(USAGE.stripTrailing());
            case "--out", "-o" -> args.length > 2
                    ? create(Path.of(args[2]))
                    : Theme.warn("Pfad fehlt: backup --out <datei>");
            default -> create(Path.of(args[1]));
        };
    }

    private String create(Path target) {
        try {
            Archive archive = runner.run(target);
            return Theme.ok("Sicherung angelegt: " + archive.file()) + "\n"
                    + size(archive.bytes()) + " · " + archive.collections() + " Collections · "
                    + archive.documents() + " Dokumente · " + archive.files() + " Dateien"
                    + "\n" + Theme.warn("Die Datei enthält Secrets — sicher aufbewahren (Rechte 0600).");
        } catch (IOException | RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return Theme.warn("Sicherung fehlgeschlagen: " + message);
        }
    }

    private String list() {
        if (directory == null || !Files.isDirectory(directory)) {
            return Theme.warn("Keine Sicherungen gefunden (" + directory + ")");
        }
        List<Path> archives;
        try (Stream<Path> files = Files.list(directory)) {
            archives = files
                    .filter(path -> path.getFileName().toString().endsWith(".zip"))
                    // Namen tragen den Zeitstempel, absteigend ist also die neueste zuerst.
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            return Theme.warn("Sicherungsordner nicht lesbar: " + e.getMessage());
        }
        if (archives.isEmpty()) {
            return Theme.warn("Keine Sicherungen in " + directory);
        }

        StringBuilder text = new StringBuilder("Sicherungen in " + directory);
        for (Path archive : archives) {
            text.append('\n').append(archive.getFileName());
            try {
                text.append("   ").append(size(Files.size(archive)))
                        .append("   ").append(TIME.format(Files.getLastModifiedTime(archive).toInstant()));
            } catch (IOException e) {
                // Einzelne Datei nicht lesbar: Name bleibt, Details fehlen.
            }
        }
        return text.toString();
    }

    private static String size(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
