package dev.bytestorm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Kommandos rund um die Plattform-Jars in {@code platforms/} (§13, §17).
 *
 * <p>Die Quelle ist austauschbar: {@link VersionSource} liefert die verfügbaren
 * Versionen (PaperMC-API), {@link Installer} lädt eine Jar. Beides kommt aus dem
 * {@code PlatformDownloader}; die CLI selbst liest nur das Verzeichnis, damit sie
 * ohne Netz und ohne API testbar bleibt.
 */
public final class PlatformCommandLine {

    /** Hilfe für {@code platform help}. */
    public static final String USAGE = """
            platform list                     Plattform-Jars in platforms/ auflisten
            platform tasks [<platform>]        Stand je Plattform: lokal, verfügbar, Aufgabe
            platform info <platform>           Details einer Plattform
            platform versions <platform>       verfügbare Versionen der Quelle
            platform install <platform> [version]  Jar laden (ohne Version die neueste stabile)
            platform update [<platform>]       neuere stabile Version laden
            """;

    /** Wie viele Versionen {@code platform versions} zeigt. */
    private static final int VERSION_LIMIT = 15;

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Liefert die verfügbaren Versionen, neueste zuerst. */
    @FunctionalInterface
    public interface VersionSource {

        /**
         * @param platform Projektname, z. B. {@code paper}
         * @return verfügbare Versionen, neueste zuerst
         */
        List<String> versions(String platform);
    }

    /** Lädt eine Plattform-Jar nach {@code platforms/}. */
    @FunctionalInterface
    public interface Installer {

        /**
         * @param platform Projektname
         * @param version  gewünschte Version oder {@code null} für die neueste stabile
         * @return die geladene Jar
         */
        Path install(String platform, String version);
    }

    private final Path directory;
    private final VersionSource versions;
    private final Installer installer;

    /**
     * @param platformsDirectory {@code platforms/}
     * @param versions           Quelle der verfügbaren Versionen
     * @param installer          Download einer Jar
     */
    public PlatformCommandLine(Path platformsDirectory, VersionSource versions, Installer installer) {
        this.directory = Objects.requireNonNull(platformsDirectory, "platformsDirectory")
                .toAbsolutePath().normalize();
        this.versions = Objects.requireNonNull(versions, "versions");
        this.installer = Objects.requireNonNull(installer, "installer");
    }

    /**
     * Führt ein Plattform-Kommando aus.
     *
     * @param tokens Kommandozeile, z. B. {@code platform install paper 1.21.4}
     * @return Ausgabe für das Terminal
     */
    public String execute(String[] tokens) {
        String command = tokens.length > 1 ? tokens[1] : "";
        String argument = tokens.length > 2 ? tokens[2] : null;
        String version = tokens.length > 3 ? tokens[3] : null;
        String lower = command.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "" , "list" -> list();
            case "help", "-h", "--help" -> USAGE.stripTrailing();
            case "tasks", "status" -> tasks(argument);
            case "info" -> info(argument);
            case "versions", "available" -> versions(argument);
            case "install" -> install(argument, version);
            case "update" -> update(argument);
            // `platform paper` verwaltet die genannte Plattform (§17).
            default -> command.isBlank()
                    ? list()
                    : command.matches(SAFE_NAME.pattern()) ? info(command) : unknown(command);
        };
    }

    private static String unknown(String command) {
        return "Unbekanntes Plattform-Kommando: " + command + " (platform help)";
    }

    private String list() {
        List<PlatformJar> jars = local();
        if (jars.isEmpty()) {
            return "Keine Plattform-Jars in " + directory
                    + System.lineSeparator() + "Laden mit 'platform install <platform> [version]'.";
        }
        StringJoiner out = new StringJoiner(System.lineSeparator());
        out.add("Datei\tPlattform\tGröße\tGeändert");
        for (PlatformJar jar : jars) {
            out.add(jar.fileName() + "\t" + jar.platform() + "\t" + size(jar) + "\t" + changed(jar.file()));
        }
        return out.toString();
    }

    /**
     * Stand je Plattform: welche Version liegt lokal, welche ist verfügbar und was
     * die nächste Aufgabe wäre ({@code aktualisieren}, {@code installieren}).
     */
    private String tasks(String platform) {
        List<PlatformJar> jars = local();
        if (platform != null && !platform.isBlank()) {
            jars = jars.stream().filter(jar -> jar.platform().equalsIgnoreCase(platform)).toList();
        }
        Set<String> platforms = new LinkedHashSet<>();
        // Zuerst die genannte Plattform, damit auch ohne lokale Jar eine Zeile entsteht.
        if (platform != null && !platform.isBlank()) {
            platforms.add(platform.toLowerCase(Locale.ROOT));
        }
        for (PlatformJar jar : jars) {
            platforms.add(jar.platform());
        }
        if (platforms.isEmpty()) {
            return "Keine Plattform bekannt — 'platform install <platform> [version]' lädt eine.";
        }

        StringJoiner out = new StringJoiner(System.lineSeparator());
        out.add("Plattform\tLokal\tVerfügbar\tAufgabe");
        for (String name : platforms) {
            Optional<String> newest = newestVersion(name);
            String localVersions = jars.stream()
                    .filter(jar -> jar.platform().equalsIgnoreCase(name))
                    .map(PlatformJar::version)
                    .sorted(Comparator.reverseOrder())
                    .findFirst()
                    .orElse("—");
            String available = newest.orElse("—");
            String task;
            if (localVersions.equals("—")) {
                task = newest.isPresent() ? "installieren" : "nicht verfügbar";
            } else if (newest.isEmpty()) {
                task = "nicht prüfbar";
            } else if (localVersions.equals(newest.get())) {
                task = "aktuell";
            } else {
                task = "aktualisieren";
            }
            out.add(name + "\t" + localVersions + "\t" + available + "\t" + task);
        }
        return out.toString();
    }

    private String info(String platform) {
        if (platform == null || platform.isBlank()) {
            return "Plattform fehlt (platform info <platform>)";
        }
        List<PlatformJar> jars = local().stream()
                .filter(jar -> jar.platform().equalsIgnoreCase(platform))
                .toList();
        StringBuilder out = new StringBuilder();
        out.append("Plattform: ").append(platform.toLowerCase(Locale.ROOT)).append(System.lineSeparator());
        out.append("Verzeichnis: ").append(directory).append(System.lineSeparator());
        if (jars.isEmpty()) {
            out.append("Lokal: keine Jar");
        } else {
            out.append("Lokal:");
            for (PlatformJar jar : jars) {
                out.append(System.lineSeparator()).append("  ").append(jar.fileName())
                        .append(" (").append(size(jar)).append(", ").append(changed(jar.file())).append(")");
            }
        }
        out.append(System.lineSeparator());
        Optional<String> newest = newestVersion(platform);
        out.append("Verfügbar: ").append(newest.orElse("nicht abfragbar"));
        return out.toString();
    }

    private String versions(String platform) {
        if (platform == null || platform.isBlank()) {
            return "Plattform fehlt (platform versions <platform>)";
        }
        List<String> available;
        try {
            available = versions.versions(platform);
        } catch (RuntimeException e) {
            return "Fehler: " + messageOf(e);
        }
        if (available.isEmpty()) {
            return "Keine Versionen für '" + platform + "' gefunden.";
        }
        List<String> shown = available.size() > VERSION_LIMIT
                ? available.subList(0, VERSION_LIMIT)
                : available;
        StringJoiner out = new StringJoiner(System.lineSeparator());
        out.add("Versionen für " + platform.toLowerCase(Locale.ROOT) + " (neueste zuerst):");
        // Eine Version je Zeile; das hält lange Listen mit den folgenden Details lesbar.
        for (String version : shown) {
            out.add("  " + version);
        }
        if (available.size() > shown.size()) {
            out.add("  … " + (available.size() - shown.size()) + " weitere");
        }
        return out.toString();
    }

    private String install(String platform, String version) {
        if (platform == null || platform.isBlank()) {
            return "Plattform fehlt (platform install <platform> [version])";
        }
        if (!platform.matches(SAFE_NAME.pattern())) {
            return "Ungültige Plattform: " + platform;
        }
        try {
            Path jar = installer.install(platform, version);
            return "Geladen: " + jar.getFileName()
                    + (version == null || version.isBlank() ? " (neueste stabile Version)" : "");
        } catch (RuntimeException e) {
            return "Fehler: " + messageOf(e);
        }
    }

    private String update(String platform) {
        List<PlatformJar> jars = local();
        Set<String> platforms = new LinkedHashSet<>();
        if (platform != null && !platform.isBlank()) {
            platforms.add(platform.toLowerCase(Locale.ROOT));
        } else {
            for (PlatformJar jar : jars) {
                platforms.add(jar.platform());
            }
        }
        if (platforms.isEmpty()) {
            return "Keine Plattform-Jars vorhanden — 'platform install <platform>' lädt eine.";
        }

        StringJoiner out = new StringJoiner(System.lineSeparator());
        for (String name : platforms) {
            Optional<String> newest;
            try {
                newest = newestVersion(name);
            } catch (RuntimeException e) {
                out.add(name + ": nicht prüfbar — " + messageOf(e));
                continue;
            }
            if (newest.isEmpty()) {
                out.add(name + ": keine stabile Version verfügbar");
                continue;
            }
            String available = newest.get();
            boolean upToDate = jars.stream()
                    .anyMatch(jar -> jar.platform().equalsIgnoreCase(name) && jar.version().equals(available));
            if (upToDate) {
                out.add(name + ": aktuell (" + available + ")");
                continue;
            }
            try {
                Path jar = installer.install(name, available);
                out.add(name + ": " + available + " geladen (" + jar.getFileName() + ")");
            } catch (RuntimeException e) {
                out.add(name + ": Fehler — " + messageOf(e));
            }
        }
        return out.toString();
    }

    private Optional<String> newestVersion(String platform) {
        List<String> available = versions.versions(platform);
        return available.isEmpty() ? Optional.empty() : Optional.of(available.get(0));
    }

    private List<PlatformJar> local() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            List<PlatformJar> jars = new ArrayList<>();
            for (Path path : entries
                    .filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                    .toList()) {
                String name = path.getFileName().toString();
                int separator = parseSeparator(name.substring(0, name.length() - 4));
                if (separator < 0) {
                    continue;
                }
                jars.add(new PlatformJar(name.substring(0, separator).toLowerCase(Locale.ROOT),
                        name.substring(separator + 1, name.length() - 4), path));
            }
            return jars;
        } catch (IOException e) {
            throw new IllegalStateException("platforms/ nicht lesbar: " + directory, e);
        }
    }

    /**
     * Sucht das {@code -} zwischen Projekt und Version, z. B. {@code paper-1.21.4}.
     *
     * @param base Dateiname ohne {@code .jar}
     * @return Index des Trennzeichens oder {@code -1}
     */
    private static int parseSeparator(String base) {
        for (int i = 0; i < base.length() - 1; i++) {
            if (base.charAt(i) == '-' && Character.isDigit(base.charAt(i + 1))) {
                return i;
            }
        }
        return -1;
    }

    private String size(PlatformJar jar) {
        try {
            return formatBytes(Files.size(jar.file()));
        } catch (IOException e) {
            return "?";
        }
    }

    private String changed(Path file) {
        try {
            FileTime time = Files.getLastModifiedTime(file);
            return TIMESTAMP.format(Instant.ofEpochMilli(time.toMillis()));
        } catch (IOException e) {
            return "?";
        }
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return "%.1f MB".formatted(bytes / (1024.0 * 1024.0));
        }
        return "%.0f KB".formatted(bytes / 1024.0);
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record PlatformJar(String platform, String version, Path file) {

        String fileName() {
            return file.getFileName().toString();
        }
    }
}
