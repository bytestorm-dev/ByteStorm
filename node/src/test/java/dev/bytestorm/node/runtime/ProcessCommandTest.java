package dev.bytestorm.node.runtime;

import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.proto.RuntimeType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessCommandTest {

    private static final Path JAVA = Path.of("/opt/java/bin/java");
    private static final Path JAR = Path.of("/srv/server.jar");

    @Test
    void buildsTheExpectedArgumentList() {
        LaunchOptions options = LaunchOptions.builder()
                .memoryMb(2048)
                .jvmArg("-XX:+UseG1GC")
                .javaArg("--demo")
                .runtime(RuntimeType.PROCESS)
                .build();

        assertEquals(List.of(
                        "/opt/java/bin/java",
                        "-Xms2048M",
                        "-Xmx2048M",
                        "-XX:+UseG1GC",
                        "-jar",
                        "/srv/server.jar",
                        "--demo",
                        "nogui"),
                ProcessCommand.build(JAVA, JAR, options));
    }

    @Test
    void appendsNoGuiOnlyOnce() {
        LaunchOptions options = LaunchOptions.builder()
                .memoryMb(512)
                .javaArg("NOGUI")
                .build();

        assertEquals(List.of("/opt/java/bin/java", "-Xms512M", "-Xmx512M",
                        "-jar", "/srv/server.jar", "NOGUI"),
                ProcessCommand.build(JAVA, JAR, options));
    }

    @Test
    void rejectsArgumentsThatCouldBreakTheLine() {
        LaunchOptions options = LaunchOptions.builder()
                .jvmArg("-Dfoo=bar\n-Devil=true")
                .build();

        assertThrows(IllegalArgumentException.class, () -> ProcessCommand.build(JAVA, JAR, options));
    }

    @Test
    void usesTheRunningJavaBinary() {
        Path java = ProcessCommand.currentJava();

        assertTrue(java.isAbsolute());
        assertTrue(java.getFileName().toString().startsWith("java"));
    }
}
