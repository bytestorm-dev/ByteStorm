package dev.bytestorm.node.crash;

import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashReportWriterTest {

    @TempDir
    Path temp;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-23T12:34:56.789Z"), ZoneOffset.UTC);

    @Test
    void writesLogTailMetadataAndMinecraftReports() throws Exception {
        Path serviceDirectory = temp.resolve("service");
        Path minecraftReports = Files.createDirectories(serviceDirectory.resolve("crash-reports"));
        Files.writeString(minecraftReports.resolve("crash-2026-10-23.txt"), "minecraft stacktrace");
        CrashReportWriter writer = new CrashReportWriter(temp.resolve("crash-reports"), clock);

        Path report = writer.write(service(), serviceDirectory, 1, "unerwarteter Exit",
                List.of("line one", "line two"));

        assertEquals("Lobby-1-20261023-123456-789", report.getFileName().toString());
        assertTrue(Files.readString(report.resolve("latest.log")).contains("line two"));
        String metadata = Files.readString(report.resolve("report.txt"));
        assertTrue(metadata.contains("Service: Lobby-1"));
        assertTrue(metadata.contains("ID: service-id"));
        assertTrue(metadata.contains("Exit-Code: 1"));
        assertTrue(metadata.contains("Grund: unerwarteter Exit"));
        assertTrue(Files.isRegularFile(report.resolve("minecraft/crash-2026-10-23.txt")));
    }

    @Test
    void anEmptyTailStillCreatesALogFile() throws Exception {
        CrashReportWriter writer = new CrashReportWriter(temp.resolve("crash-reports"), clock);

        Path report = writer.write(service(), temp.resolve("service"), 137, "gekillt", List.of());

        assertEquals("", Files.readString(report.resolve("latest.log")));
        assertTrue(Files.readString(report.resolve("report.txt")).contains("Logzeilen: 0"));
    }

    @Test
    void collidingTimestampsGetASuffix() throws Exception {
        CrashReportWriter writer = new CrashReportWriter(temp.resolve("crash-reports"), clock);

        Path first = writer.write(service(), temp.resolve("service"), 1, "crash", List.of());
        Path second = writer.write(service(), temp.resolve("service"), 1, "crash", List.of());

        assertEquals("Lobby-1-20261023-123456-789", first.getFileName().toString());
        assertEquals("Lobby-1-20261023-123456-789-1", second.getFileName().toString());
    }

    @Test
    void withoutMinecraftDirectoryNoCopyHappens() throws Exception {
        CrashReportWriter writer = new CrashReportWriter(temp.resolve("crash-reports"), clock);

        Path report = writer.write(service(), temp.resolve("service"), 1, "crash", List.of());

        assertTrue(Files.notExists(report.resolve("minecraft")));
    }

    private ServiceInfo service() {
        return ServiceInfo.newBuilder()
                .setId("service-id")
                .setName("Lobby-1")
                .setGroup("Lobby")
                .setRuntime(RuntimeType.PROCESS)
                .setState(ServiceState.CRASHED)
                .setPort(30001)
                .build();
    }
}
