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
 */
public final class CrashReportWriter {

    private static final DateTimeFormatter DIRECTORY_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final Path root;
    private final Clock clock;

    /**
     * @param crashReportsRoot Wurzel {@code crash-reports/}
     */
    public CrashReportWriter(Path crashReportsRoot) {
        this(crashReportsRoot, Clock.systemUTC());
    }

    CrashReportWriter(Path crashReportsRoot, Clock clock) {
        this.root = Objects.requireNonNull(crashReportsRoot, "crashReportsRoot").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
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
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException("Crash-Bericht für " + service.getName() + " nicht schreibbar", e);
        }
    }

    private Path createDirectory(String serviceName) throws IOException {
        Files.createDirectories(root);
        String base = sanitize(serviceName) + "-" + DIRECTORY_TIME.format(Instant.now(clock));
        Path directory = root.resolve(base);
        int suffix = 1;
        while (Files.exists(directory)) {
            directory = root.resolve(base + "-" + suffix++);
        }
        return Files.createDirectories(directory);
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

    private static String sanitize(String name) {
        String sanitized = name == null || name.isBlank() ? "service" : name.trim();
        return sanitized.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
