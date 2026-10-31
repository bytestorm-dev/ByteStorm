package dev.bytestorm.launcher.download;

import dev.bytestorm.launcher.layout.CloudLayout;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stellt die Fremd-Bibliotheken der Cloud vor dem Start bereit (§5.1).
 *
 * <p>Die Distribution enthält nur noch die ByteStorm-Jars und eine Liste
 * {@code lib/dependencies.txt} mit Koordinaten und SHA-256 aller Fremd-Jars. Beim
 * Start prüft dieser Installer jede Datei, lädt fehlende oder beschädigte von
 * Maven Central nach und schreibt {@code lib/.manifest.json} mit dem Ist-Zustand.
 *
 * <p>Er läuft, bevor der {@link dev.bytestorm.launcher.LibClassLoader} geöffnet
 * wird, und benutzt deshalb ausschließlich JDK-Klassen. Fehler beenden den Start
 * nicht sofort: Der Installer sammelt alle fehlenden Dateien mit Zielpfad,
 * Prüfsumme und Quelle ein, damit der Launcher sie in einem Block ausgeben kann.
 */
public final class LibraryInstaller {

    /** Liste der nachzuladenden Abhängigkeiten in {@code lib/}. */
    public static final String LIST_FILE = "dependencies.txt";

    /** Dateiname im Manifest; das Format schreibt {@link #writeManifest}. */
    private static final Pattern FILE_ENTRY = Pattern.compile("\\\"file\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    /**
     * Eine Bibliothek, die fehlt und nicht geladen werden konnte.
     *
     * @param coordinates Maven-Koordinaten
     * @param fileName    Dateiname im {@code lib/}-Verzeichnis
     * @param target      Zielpfad
     * @param sha256      erwartete Prüfsumme
     * @param source      Download-URL
     * @param reason      Grund, z. B. ein HTTP-Fehler
     */
    public record Missing(String coordinates, String fileName, Path target,
                          String sha256, URI source, String reason) {

        /**
         * @return mehrzeilige Anleitung zum manuellen Ablegen
         */
        public String describe() {
            String newline = System.lineSeparator();
            return "- " + fileName + " (" + coordinates + ")" + newline
                    + "  Ziel:    " + target + newline
                    + "  SHA-256: " + sha256 + newline
                    + "  Quelle:  " + source + newline
                    + "  Grund:   " + reason;
        }
    }

    /**
     * Ergebnis eines Laufs.
     *
     * @param checked    geprüfte Abhängigkeiten
     * @param downloaded tatsächlich geladene Dateien
     * @param missing    Bibliotheken, die nicht bereitstehen
     */
    public record Result(int checked, int downloaded, List<Missing> missing) {

        /**
         * @param checked    geprüfte Abhängigkeiten
         * @param downloaded geladene Dateien
         * @param missing    fehlende Bibliotheken
         */
        public Result {
            missing = List.copyOf(missing);
        }

        /**
         * @return {@code true}, wenn alle Bibliotheken bereitstehen
         */
        public boolean complete() {
            return missing.isEmpty();
        }
    }

    private LibraryInstaller() {
    }

    /**
     * @param layout Cloud-Layout
     * @param out    Ausgabe für Fortschritt und Hinweise
     * @return das Ergebnis; ohne {@code lib/dependencies.txt} ein leeres
     */
    public static Result install(CloudLayout layout, PrintStream out) {
        return install(layout, LibDownloader.MAVEN_CENTRAL, out);
    }

    /**
     * @param layout     Cloud-Layout
     * @param repository Repository-Basis
     * @param out        Ausgabe für Fortschritt und Hinweise
     * @return das Ergebnis; ohne {@code lib/dependencies.txt} ein leeres
     */
    public static Result install(CloudLayout layout, URI repository, PrintStream out) {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(out, "out");

        Path list = layout.lib().resolve(LIST_FILE);
        if (!Files.isRegularFile(list)) {
            // Ältere Distributionen bringen ihre Jars selbst mit.
            return new Result(0, 0, List.of());
        }

        List<Dependency> dependencies = read(list);
        LibDownloader downloader = new LibDownloader(layout, repository);
        // Geprüfte Dateien bleiben ungemeldet: Ein normaler Start soll schwiegend
        // hochfahren, nur ein echter Download erzeugt Zeilen.
        DownloadProgressPrinter printer = new DownloadProgressPrinter(out, System.console() != null);
        List<LibDownloader.Outcome> outcomes = downloader.download(dependencies,
                status -> {
                    if (status.downloaded()) {
                        printer.accept(status);
                    }
                });

        List<Missing> missing = new ArrayList<>();
        int downloaded = 0;
        for (LibDownloader.Outcome outcome : outcomes) {
            if (outcome.failed()) {
                missing.add(new Missing(
                        outcome.dependency().coordinates(),
                        outcome.fileName(),
                        outcome.file(),
                        outcome.dependency().sha256(),
                        downloader.uriOf(outcome.dependency()),
                        describe(outcome.error())));
            } else if (outcome.downloaded()) {
                downloaded++;
            }
        }
        if (missing.isEmpty()) {
            if (downloaded > 0) {
                out.println("Bibliotheken bereit: " + outcomes.size()
                        + " geprüft, " + downloaded + " geladen.");
                out.flush();
            }
            // Nach einem Update dürfen alte Fassungen nicht neben den neuen liegen:
            // Der Classloader würde sonst beide Versionen sehen.
            removeStale(layout, outcomes, out);
            writeManifest(layout, outcomes, out);
        }
        return new Result(outcomes.size(), downloaded, missing);
    }

    /**
     * Liest die Liste der Abhängigkeiten.
     *
     * <p>Je Zeile {@code groupId:artifactId:version[:classifier]:sha256}; leere
     * Zeilen und Zeilen mit {@code #} werden übergangen.
     *
     * @param file Liste
     * @return die Abhängigkeiten in Dateireihenfolge
     * @throws DownloadException wenn die Liste nicht lesbar oder eine Zeile ungültig ist
     */
    static List<Dependency> read(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new DownloadException("Abhängigkeitsliste nicht lesbar: " + file, e);
        }
        List<Dependency> dependencies = new ArrayList<>(lines.size());
        int number = 0;
        for (String line : lines) {
            number++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            dependencies.add(parse(trimmed, file, number));
        }
        return dependencies;
    }

    private static Dependency parse(String line, Path file, int number) {
        String[] parts = line.split(":", -1);
        if (parts.length != 4 && parts.length != 5) {
            throw new DownloadException("Ungültige Zeile " + number + " in " + file
                    + ": erwartet group:artifact:version[:classifier]:sha256");
        }
        String groupId = parts[0];
        String artifactId = parts[1];
        String version = parts[2];
        String classifier = parts.length == 5 ? parts[3] : null;
        String sha256 = parts.length == 5 ? parts[4] : parts[3];
        try {
            return new Dependency(groupId, artifactId, version, classifier, sha256);
        } catch (IllegalArgumentException e) {
            throw new DownloadException("Ungültige Zeile " + number + " in " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Entfernt Dateien, die das vorherige Manifest als installiert ausweist, die
     * aber nicht mehr zur aktuellen Liste gehören. Selbst abgelegte Jars stehen
     * nicht im Manifest und bleiben deshalb unangetastet.
     */
    private static void removeStale(CloudLayout layout, List<LibDownloader.Outcome> outcomes, PrintStream out) {
        List<String> previous = previousFiles(layout.lib().resolve(LibManifest.FILE_NAME));
        if (previous.isEmpty()) {
            return;
        }
        List<String> current = outcomes.stream()
                .filter(outcome -> !outcome.failed())
                .map(LibDownloader.Outcome::fileName)
                .toList();
        int removed = 0;
        for (String file : previous) {
            if (current.contains(file)) {
                continue;
            }
            Path target = layout.lib().resolve(file).normalize();
            if (!target.startsWith(layout.lib()) || !Files.isRegularFile(target)) {
                continue;
            }
            try {
                Files.delete(target);
                removed++;
            } catch (IOException ignored) {
                // Was sich nicht löschen lässt, bleibt liegen; der nächste Start versucht es erneut.
            }
        }
        if (removed > 0) {
            out.println("Alte Bibliotheken entfernt: " + removed);
            out.flush();
        }
    }

    /**
     * @param manifest {@code lib/.manifest.json}
     * @return die dort verzeichneten Dateinamen, leer wenn das Manifest fehlt
     */
    static List<String> previousFiles(Path manifest) {
        if (!Files.isRegularFile(manifest)) {
            return List.of();
        }
        String text;
        try {
            text = Files.readString(manifest);
        } catch (IOException e) {
            return List.of();
        }
        Matcher matcher = FILE_ENTRY.matcher(text);
        List<String> files = new ArrayList<>();
        while (matcher.find()) {
            files.add(matcher.group(1));
        }
        return files;
    }

    /**
     * Schreibt den Ist-Zustand nach {@code lib/.manifest.json}. Das Format ist
     * dasselbe, das {@link LibManifest} liest; geschrieben wird es hier ohne
     * Jackson, weil der Installer vor dem Classloader läuft.
     */
    private static void writeManifest(CloudLayout layout, List<LibDownloader.Outcome> outcomes, PrintStream out) {
        StringBuilder json = new StringBuilder(128 + outcomes.size() * 180);
        json.append("{\n  \"dependencies\" : [");
        boolean first = true;
        for (LibDownloader.Outcome outcome : outcomes) {
            if (outcome.failed()) {
                continue;
            }
            json.append(first ? "\n" : ",\n");
            first = false;
            Dependency dependency = outcome.dependency();
            json.append("    {\n")
                    .append("      \"file\" : \"").append(escape(dependency.fileName())).append("\",\n")
                    .append("      \"groupId\" : \"").append(escape(dependency.groupId())).append("\",\n")
                    .append("      \"artifactId\" : \"").append(escape(dependency.artifactId())).append("\",\n")
                    .append("      \"version\" : \"").append(escape(dependency.version())).append("\",\n")
                    .append("      \"sha256\" : \"").append(escape(dependency.sha256())).append("\",\n")
                    .append("      \"bytes\" : ").append(outcome.bytes()).append("\n")
                    .append("    }");
        }
        json.append("\n  ]\n}\n");

        Path target = layout.lib().resolve(LibManifest.FILE_NAME);
        Path temp = layout.lib().resolve(LibManifest.FILE_NAME + ".tmp");
        try {
            Files.writeString(temp, json);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Das Manifest dient späteren Updates; es darf den Start nicht verhindern.
            out.println("Hinweis: Manifest nicht geschrieben (" + target + "): " + e.getMessage());
            out.flush();
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // harmlos: wird beim nächsten Lauf ersetzt
            }
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
