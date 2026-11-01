package dev.bytestorm.controller.template;

import dev.bytestorm.node.template.TemplateLayers;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static dev.bytestorm.node.template.TemplatePreparer.PREPARED_MARKER;

/**
 * Verwaltet die Template-Ordner unter {@code templates/} (§10, §17).
 *
 * <p>Quelle der Wahrheit ist das Dateisystem: {@code TemplatePreparer} legt den
 * Service-Ordner an, indem er die Ebenen {@code ALL} → {@code PROXY}/{@code SERVER}
 * → {@code FALLBACK} → die Templates der Gruppe als Ordner übereinander kopiert.
 * Die Kommandos legen solche Ordner an, kopieren sie, spiegeln einen
 * Service-Ordner zurück ({@code sync}) und löschen sie wieder.
 *
 * <p>Die festen Ebenen aus {@link TemplateLayers} existieren immer; sie werden
 * wie eigene Templates behandelt, aber als solche gekennzeichnet. Wird eine
 * feste Ebene gelöscht, legt der nächste Cloud-Start die Standard-Dateien
 * darin wieder an.
 *
 * <p>Metadaten mit Hash und Änderungsbeobachtung (Hash, Watcher) folgen in einem
 * eigenen Schritt; {@code templates list} rechnet Größe und Änderungszeit
 * deshalb direkt aus den Ordnern.
 */
@Slf4j
public final class TemplateManager {

    /**
     * Zulässige Templatenamen; sie werden zu Ordnernamen unter {@code templates/}.
     */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    /** Verzeichnisse, die beim Rück-Sync nicht ins Template wandern. */
    private static final Set<String> RUNTIME_DIRECTORIES = Set.of("logs", "crash-reports");

    private final Path root;
    private final Function<String, Optional<Path>> serviceDirectory;

    /**
     * @param templatesRoot    Ordner mit den Template-Ebenen
     * @param serviceDirectory findet den Ordner eines Services anhand seines Namens
     */
    public TemplateManager(Path templatesRoot, Function<String, Optional<Path>> serviceDirectory) {
        this.root = Objects.requireNonNull(templatesRoot, "templatesRoot")
                .toAbsolutePath().normalize();
        this.serviceDirectory = Objects.requireNonNull(serviceDirectory, "serviceDirectory");
    }

    /**
     * @param name Templatename
     * @return {@code true}, wenn der Name zu einer festen Ebene gehört
     */
    public static boolean isFixedLayer(String name) {
        return TemplateLayers.ALL.equals(name) || TemplateLayers.PROXY.equals(name)
                || TemplateLayers.SERVER.equals(name) || TemplateLayers.FALLBACK.equals(name);
    }

    /**
     * @return alle Templates, alphabetisch nach Name
     */
    public List<TemplateEntry> list() {
        Path root = this.root;
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            List<TemplateEntry> templates = new ArrayList<>();
            for (Path entry : entries.filter(Files::isDirectory).sorted().toList()) {
                templates.add(entry(entry));
            }
            templates.sort(Comparator.comparing(TemplateEntry::name));
            return List.copyOf(templates);
        } catch (IOException e) {
            throw new UncheckedIOException("templates/ nicht lesbar: " + root, e);
        }
    }

    /**
     * @param name Templatename
     * @return das Template, falls vorhanden
     */
    public Optional<TemplateEntry> get(String name) {
        Path directory = resolve(name);
        return Files.isDirectory(directory) ? Optional.of(entry(directory)) : Optional.empty();
    }

    /**
     * Legt einen leeren Template-Ordner an.
     *
     * @param name Templatename
     * @return das angelegte Template
     * @throws IllegalArgumentException wenn der Name ungültig ist oder schon existiert
     */
    public TemplateEntry create(String name) {
        Path directory = resolve(name);
        if (Files.exists(directory)) {
            throw new IllegalArgumentException("Template '" + name + "' existiert bereits");
        }
        try {
            Files.createDirectories(directory);
            log.info("Template '{}' angelegt", name);
            return entry(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Template '" + name + "' konnte nicht angelegt werden", e);
        }
    }

    /**
     * Kopiert ein Template unter einen neuen Namen.
     *
     * @param source Quelltemplate
     * @param target Zielname
     * @return das neue Template
     * @throws IllegalArgumentException wenn Quelle oder Ziel nicht passen
     */
    public TemplateEntry copy(String source, String target) {
        Path from = resolve(source);
        if (!Files.isDirectory(from)) {
            throw new IllegalArgumentException("Unbekanntes Template: " + source);
        }
        Path to = resolve(target);
        if (Files.exists(to)) {
            throw new IllegalArgumentException("Template '" + target + "' existiert bereits");
        }
        try {
            Files.createDirectories(to);
            copyTree(from, to, relative -> false);
            log.info("Template '{}' nach '{}' kopiert", source, target);
            return entry(to);
        } catch (IOException e) {
            throw new UncheckedIOException("Template '" + source + "' konnte nicht kopiert werden", e);
        }
    }

    /**
     * Spiegelt den Ordner eines Services in ein Template (§10).
     *
     * <p>Übernommen werden Konfiguration und Inhalte; Laufzeitdaten bleiben außen
     * vor: {@code logs/}, {@code crash-reports/} und Weltordner ({@code world*})
     * wandern nicht ins Template, ebenso wenig die Plattform-Jar im Wurzelordner
     * und die Bridge-Jar, die der Node selbst nach {@code plugins/} legt.
     *
     * @param service Service-Name
     * @param target  Zieltemplate
     * @return Anzahl übernommener Dateien und das aktualisierte Template
     * @throws IllegalArgumentException wenn Service oder Template nicht bekannt sind
     */
    public SyncResult sync(String service, String target) {
        Objects.requireNonNull(service, "service");
        Path template = resolve(target);
        if (!Files.isDirectory(template)) {
            throw new IllegalArgumentException("Unbekanntes Template: " + target);
        }
        Path directory = serviceDirectory.apply(service)
                .orElseThrow(() -> new IllegalArgumentException("Kein Service-Ordner für '" + service
                        + "' gefunden (läuft der Service?)"));
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Service-Ordner fehlt: " + directory);
        }
        try {
            int files = copyTree(directory, template, TemplateManager::skipOnSync);
            log.info("Service-Ordner von '{}' nach Template '{}' gespiegelt ({} Dateien)",
                    service, target, files);
            return new SyncResult(files, entry(template));
        } catch (IOException e) {
            throw new UncheckedIOException("Service-Ordner von '" + service + "' nicht kopierbar", e);
        }
    }

    /**
     * Löscht ein Template samt Inhalt.
     *
     * @param name Templatename
     * @return {@code true}, wenn eine feste Ebene gelöscht wurde
     * @throws IllegalArgumentException wenn der Name ungültig ist oder das Template fehlt
     */
    public boolean delete(String name) {
        Path directory = resolve(name);
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Unbekanntes Template: " + name);
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Template '" + name + "' nicht löschbar", e);
        }
        log.info("Template '{}' gelöscht", name);
        return isFixedLayer(name);
    }

    /**
     * @param relative Pfad relativ zum Service-Ordner
     * @return {@code true}, wenn die Datei beim Rück-Sync ausgelassen wird
     */
    private static boolean skipOnSync(Path relative) {
        if (relative.getNameCount() == 1 && relative.getFileName().toString().equals(PREPARED_MARKER)) {
            // Gehört zur Vorbereitung, nicht zur Konfiguration des Services.
            return true;
        }
        String first = relative.getName(0).toString();
        if (RUNTIME_DIRECTORIES.contains(first) || first.startsWith("world")) {
            return true;
        }
        String name = relative.getFileName().toString();
        if (!name.endsWith(".jar")) {
            return false;
        }
        // Plattform-Jar im Wurzelordner und die Bridge-Jar, die der Node installiert.
        return relative.getNameCount() == 1
                || (relative.getNameCount() == 2 && first.equals("plugins") && name.startsWith("bridge-"));
    }

    /**
     * @param source      Quellordner
     * @param target      Zielordner
     * @param skip        Pfade relativ zur Quelle, die ausgelassen werden
     * @return Anzahl kopierter Dateien
     */
    private static int copyTree(Path source, Path target, Predicate<Path> skip) throws IOException {
        return copyTree(source, target, Path.of(""), skip);
    }

    private static int copyTree(Path source, Path target, Path relative, Predicate<Path> skip)
            throws IOException {
        int copied = 0;
        try (Stream<Path> entries = Files.list(source)) {
            for (Path entry : entries.sorted().toList()) {
                Path child = relative.resolve(entry.getFileName().toString());
                if (skip.test(child)) {
                    continue;
                }
                Path destination = target.resolve(entry.getFileName().toString());
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                    copied += copyTree(entry, destination, child, skip);
                } else {
                    Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                    copied++;
                }
            }
        }
        return copied;
    }

    private TemplateEntry entry(Path directory) {
        int files = 0;
        long bytes = 0;
        Instant lastModified = Instant.EPOCH;
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                files++;
                bytes += Files.size(path);
                Instant changed = Files.getLastModifiedTime(path).toInstant();
                if (changed.isAfter(lastModified)) {
                    lastModified = changed;
                }
            }
            if (files == 0) {
                lastModified = Files.getLastModifiedTime(directory).toInstant();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Template-Ordner nicht lesbar: " + directory, e);
        }
        return new TemplateEntry(directory.getFileName().toString(), files, bytes, lastModified,
                isFixedLayer(directory.getFileName().toString()));
    }

    /**
     * Löst einen Templatenamen gegen {@code templates/} auf und weist Pfade ab,
     * die aus dem Ordner herausführen.
     */
    private Path resolve(String name) {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Templatename muss 1 bis 32 Zeichen aus Buchstaben, "
                    + "Ziffern, '-' und '_' enthalten: '" + name + "'");
        }
        Path path = root.resolve(name).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Templatename zeigt aus templates/ heraus: '" + name + "'");
        }
        return path;
    }

    /**
     * Metadaten eines Templates, direkt aus dem Ordner gelesen.
     *
     * @param name         Templatename
     * @param files        Anzahl der Dateien
     * @param bytes        Gesamtgröße in Bytes
     * @param lastModified jüngste Änderung, {@code EPOCH} bei leerem Ordner ohne Zeitstempel
     * @param fixed        {@code true} für die festen Ebenen aus {@link TemplateLayers}
     */
    public record TemplateEntry(String name, int files, long bytes, Instant lastModified, boolean fixed) {
    }

    /**
     * Ergebnis eines Rück-Syncs.
     *
     * @param files    Anzahl übernommener Dateien
     * @param template das aktualisierte Template
     */
    public record SyncResult(int files, TemplateEntry template) {
    }
}
