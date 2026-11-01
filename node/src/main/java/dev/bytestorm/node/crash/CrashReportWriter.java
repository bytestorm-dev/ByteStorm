package dev.bytestorm.node.crash;

import dev.bytestorm.proto.ServiceInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Schreibt einen Crash-Bericht nach
 * {@code crash-reports/<Service>-<Zeitstempel>/} (§12.5).
 *
 * <p>Enthalten sind die letzte Logzeile, Exit-Code, Service-Dump und — falls
 * vorhanden — die Minecraft-Crash-Reports des Service-Ordners. Der Bericht wird
 * auch dann geschrieben, wenn keine Logzeilen vorliegen.
 *
 * <p>Je Service bleiben die neuesten {@value #DEFAULT_MAX_REPORTS} Berichte
 * stehen. Ein Crash-Sturm schreibt sonst pro Versuch einen Ordner mit kopierten
 * Weltdaten und füllt die Platte, ohne dass es jemandem auffällt.
 */
public final class CrashReportWriter {

    private static final DateTimeFormatter DIRECTORY_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    /** Wie viele Berichte je Service aufbewahrt werden. */
    static final int DEFAULT_MAX_REPORTS = 20;

    /** Länge des Zeitstempels {@code yyyyMMdd-HHmmss-SSS}. */
    private static final int DIRECTORY_TIME_LENGTH = 19;

    private final Path root;
    private final Clock clock;
    private final int maxReports;

    /**
     * @param crashReportsRoot Wurzel {@code crash-reports/}
     */
    public CrashReportWriter(Path crashReportsRoot) {
        this(crashReportsRoot, Clock.systemUTC(), DEFAULT_MAX_REPORTS);
    }

    CrashReportWriter(Path crashReportsRoot, Clock clock) {
        this(crashReportsRoot, clock, DEFAULT_MAX_REPORTS);
    }

    CrashReportWriter(Path crashReportsRoot, Clock clock, int maxReports) {
        this.root = Objects.requireNonNull(crashReportsRoot, "crashReportsRoot").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxReports = Math.max(1, maxReports);
    }

    /**
     * @param service          Service zum Crash-Zeitpunkt
     * @param serviceDirectory Ordner des Services, für Minecraft-Crash-Reports
     * @param exitCode         Exit-Code
     * @param reason           Ursache, z. B. {@code unerwarteter Exit}
     * @param logTail          letzte Logzeilen, älteste zuerst
     * @return der angelegte Berichtsordner
     */
    public Path write(ServiceInfo service, Path serviceDirectory, int exitCode, String reason, List<String> logTail) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(serviceDirectory, "serviceDirectory");
        Objects.requireNonNull(reason, "reason");
        List<String> tail = logTail == null ? List.of() : List.copyOf(logTail);

        try {
            Path directory = createDirectory(service.getName());
            Files.writeString(directory.resolve("latest.log"), String.join(System.lineSeparator(), tail)
                    + (tail.isEmpty() ? "" : System.lineSeparator()), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("report.txt"), report(service, exitCode, reason, tail.size()),
                    StandardCharsets.UTF_8);
            copyMinecraftReports(serviceDirectory, directory);
            prune(service.getName());
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException("Crash-Bericht für " + service.getName() + " nicht schreibbar", e);
        }
    }

    /**
     * Legt den Berichtsordner an. Der Name endet immer auf eine laufende Nummer:
     * Ein wiederverwendeter Name ohne Suffix würde nach dem Aufräumen vor die
     * neueren {@code -n}-Ordner springen und beim nächsten Löschen selbst als
     * ältester gelten.
     */
    private Path createDirectory(String serviceName) throws IOException {
        Files.createDirectories(root);
        String base = sanitize(serviceName) + "-" + DIRECTORY_TIME.format(Instant.now(clock));
        return Files.createDirectories(root.resolve(base + "-" + nextSuffix(base)));
    }

    /**
     * @param base Verzeichnisname ohne laufende Nummer
     * @return die nächste freie Nummer über allen vorhandenen Berichten
     */
    private int nextSuffix(String base) throws IOException {
        int max = -1;
        try (Stream<Path> entries = Files.list(root)) {
            for (Path path : entries.toList()) {
                String name = path.getFileName().toString();
                if (!name.startsWith(base + "-")) {
                    continue;
                }
                try {
                    max = Math.max(max, Integer.parseInt(name.substring(base.length() + 1)));
                } catch (NumberFormatException e) {
                    // Kein Zähler, sondern ein anderer Name mit gleichem Präfix.
                }
            }
        }
        return max + 1;
    }

    private void copyMinecraftReports(Path serviceDirectory, Path reportDirectory) throws IOException {
        Path source = serviceDirectory.resolve("crash-reports");
        if (!Files.isDirectory(source)) {
            return;
        }
        Path target = Files.createDirectories(reportDirectory.resolve("minecraft"));
        try (Stream<Path> files = Files.list(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private String report(ServiceInfo service, int exitCode, String reason, int logLines) {
        return """
                Service: %s
                ID: %s
                Gruppe: %s
                Runtime: %s
                Port: %d
                Status: %s
                Exit-Code: %d
                Grund: %s
                Zeit: %s
                Logzeilen: %d
                """.formatted(
                service.getName(),
                service.getId(),
                service.getGroup(),
                service.getRuntime(),
                service.getPort(),
                service.getState(),
                exitCode,
                reason,
                Instant.now(clock),
                logLines);
    }

    /**
     * Entfernt die ältesten Berichte eines Services, bis nur noch {@code maxReports}
     * übrig sind. Sortiert wird über den Verzeichnisnamen: Er beginnt mit dem
     * Service-Namen und trägt dahinter den UTC-Zeitstempel (mit Suffix bei
     * Gleichstand).
     *
     * @param serviceName Service, dessen Berichte aufgeräumt werden
     */
    private void prune(String serviceName) {
        String prefix = sanitize(serviceName) + "-";
        try (Stream<Path> entries = Files.list(root)) {
            List<Path> reports = entries
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .sorted(Comparator.comparing(path -> order(path.getFileName().toString(), prefix)))
                    .toList();
            for (int i = 0; i < reports.size() - maxReports; i++) {
                delete(reports.get(i));
            }
        } catch (IOException e) {
            // Aufräumen ist Hygiene; ein Fehler darf den Bericht nicht verhindern.
        }
    }

    /**
     * Sortierschlüssel eines Berichtsordners: Zeitstempel und Suffix.
     *
     * <p>Rein lexikografisch reicht nicht: Wird der älteste Ordner gelöscht, vergibt
     * ein neuer Crash wieder den unsuffigierten Namen — der spränge dann vor die
     * vorhandenen {@code -1}/{@code -2} und gälte als ältester. Genau der neue
     * Bericht würde beim nächsten Aufräumen gelöscht.
     *
     * @param name   Verzeichnisname
     * @param prefix Service-Präfix inklusive Bindestrich
     * @return Schlüssel, der nach Zeitstempel und danach nach Suffix sortiert
     */
    private static String order(String name, String prefix) {
        String rest = name.substring(prefix.length());
        int suffix = -1;
        int dash = rest.lastIndexOf('-');
        if (rest.length() > DIRECTORY_TIME_LENGTH && dash >= DIRECTORY_TIME_LENGTH) {
            try {
                suffix = Integer.parseInt(rest.substring(dash + 1));
                rest = rest.substring(0, dash);
            } catch (NumberFormatException e) {
                // Kein Suffix, sondern Teil des Zeitstempels.
            }
        }
        return rest + "-" + String.format("%05d", suffix);
    }

    private static void delete(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // s. o. — der nächste Bericht räumt erneut auf.
        }
    }

    private static String sanitize(String name) {
        String sanitized = name == null || name.isBlank() ? "service" : name.trim();
        return sanitized.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
