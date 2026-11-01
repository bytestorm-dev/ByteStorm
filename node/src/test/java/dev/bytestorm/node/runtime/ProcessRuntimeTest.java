package dev.bytestorm.node.runtime;

import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.api.service.ServiceHandle;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.proto.LogLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ProcessRuntimeTest {

    private static final String MAIN_CLASS = "dev.bytestorm.testchild.TestServer";

    @TempDir
    Path temp;

    private final List<ServiceHandle> started = new ArrayList<>();
    private final Map<String, AtomicBoolean> publisherClosed = new ConcurrentHashMap<>();
    private final List<LogLine> published = Collections.synchronizedList(new ArrayList<>());
    private ProcessRuntime runtime;

    @AfterEach
    void cleanup() {
        if (runtime != null) {
            started.forEach(handle -> {
                try {
                    runtime.kill(handle);
                } catch (RuntimeException ignored) {
                    // Testende: nur aufräumen.
                }
            });
        }
    }

    @Test
    void startsStreamsConsoleOutputAndStopsGracefully() throws Exception {
        Path work = temp.resolve("work");
        Path jar = createJar(work, false);
        Path bridge = Files.writeString(work.resolve("ByteStormBridge.jar"), "bridge");
        ServiceLaunch launch = launch(work, jar, bridge);

        ServiceHandle handle = start(launch);

        assertTrue(handle.isAlive());
        assertTrue(Long.parseLong(handle.nativeId()) > 0);
        await(() -> logsContain(handle,
                        "started file=" + BridgeEnvironment.FILE_NAME + " passwordEnv=false args=nogui"),
                "Kindprozess hat den Dateinamen, aber kein Passwort in der Umgebung bekommen");
        assertTrue(Files.isRegularFile(work.resolve("service/plugins/ByteStormBridge.jar")));
        await(() -> logsContain(handle, "[ERR] stderr-marker"), "stderr wurde nicht gelesen");

        runtime.sendCommand(handle, "hello");
        await(() -> logsContain(handle, "echo:hello"), "Konsolenantwort fehlt");

        runtime.stop(handle, Duration.ofSeconds(5));

        await(() -> !handle.isAlive(), "Prozess läuft nach stop noch");
        await(() -> logsContain(handle, "stopping"), "Stopp-Ausgabe fehlt");
        await(() -> launch.port().released(), "Port wurde nach dem Stop nicht freigegeben");
        assertEquals(0, ((ExitAware) runtime).onExit(handle).get(5, TimeUnit.SECONDS));
        await(() -> publisherClosed.getOrDefault("Lobby-1", new AtomicBoolean()).get(),
                "Log-Publisher wurde nicht geschlossen");
        assertTrue(Files.readString(temp.resolve("logs/Lobby-1/latest.log")).contains("echo:hello"));
        assertFalse(published.isEmpty());
        assertEquals("Lobby-1", published.get(0).getService());
        assertThrows(ServiceRuntimeException.class, () -> runtime.sendCommand(handle, "late"));
        runtime.stop(handle, Duration.ofSeconds(1));
    }

    @Test
    void killsAProcessThatIgnoresTheStopCommand() throws Exception {
        Path work = temp.resolve("work-ignore");
        Path jar = createJar(work, true);
        ServiceLaunch launch = launch(work, jar, null);

        ServiceHandle handle = start(launch);
        await(() -> logsContain(handle, "started"), "Kindprozess nicht bereit");

        runtime.stop(handle, Duration.ofMillis(300));

        await(() -> !handle.isAlive(), "Prozess wurde nach dem Timeout nicht gekillt");
        await(() -> launch.port().released(), "Port wurde nach dem Kill nicht freigegeben");
    }

    @Test
    void failsWhenThePlatformJarIsMissing() throws Exception {
        Path work = temp.resolve("work-missing");
        Files.createDirectories(work);
        ServiceLaunch launch = launch(work, work.resolve("missing.jar"), null);

        ServiceRuntimeException error = assertThrows(ServiceRuntimeException.class,
                () -> start(launch));

        assertTrue(error.getMessage().contains("Platform-Jar fehlt"), error.getMessage());
    }

    private ServiceHandle start(ServiceLaunch launch) {
        runtime = new ProcessRuntime(temp.resolve("logs"), name -> {
            AtomicBoolean closed = new AtomicBoolean();
            publisherClosed.put(name, closed);
            return new LogPublisher() {
                @Override
                public void publish(LogLine line) {
                    published.add(line);
                }

                @Override
                public void close() {
                    closed.set(true);
                }
            };
        }, ProcessCommand.currentJava(), 100);
        ServiceHandle handle = runtime.start(launch);
        started.add(handle);
        return handle;
    }

    private ServiceLaunch launch(Path work, Path platformJar, Path bridgeJar) {
        return new ServiceLaunch(ServiceId.random(), "Lobby-1", PortLease.fixed(30001), work.resolve("service"),
                platformJar, bridgeJar,
                LaunchOptions.builder().memoryMb(64).build(),
                new RedisSettings("redis://localhost:6379", "secret"));
    }

    private boolean logsContain(ServiceHandle handle, String text) {
        return runtime.logs(handle).stream().anyMatch(line -> line.contains(text));
    }

    private Path createJar(Path work, boolean ignoreStop) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null, "Test-JVM enthält keinen Java-Compiler");

        Path source = work.resolve("src/" + MAIN_CLASS.replace('.', '/') + ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, mainSource(ignoreStop));
        Path classes = Files.createDirectories(work.resolve("classes"));
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int result = compiler.run(null, null, errors, "-d", classes.toString(), source.toString());
        if (result != 0) {
            fail("Testklasse nicht kompilierbar: " + errors.toString(StandardCharsets.UTF_8));
        }

        Path jar = work.resolve("server.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            out.write(("Manifest-Version: 1.0\r\nMain-Class: " + MAIN_CLASS + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            try (var files = Files.walk(classes)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = classes.relativize(file).toString().replace(File.separatorChar, '/');
                    out.putNextEntry(new JarEntry(name));
                    out.write(Files.readAllBytes(file));
                    out.closeEntry();
                }
            }
        }
        return jar;
    }

    private static String mainSource(boolean ignoreStop) {
        String stopHandling = ignoreStop
                ? "      System.out.println(\"ignoring:\" + line);"
                : "      if (line.equals(\"stop\")) { System.out.println(\"stopping\"); return; }";
        return """
                package dev.bytestorm.testchild;

                import java.io.BufferedReader;
                import java.io.InputStreamReader;

                public final class TestServer {
                    public static void main(String[] args) throws Exception {
                        System.out.println("started file=" + System.getenv("BYTESTORM_BRIDGE_FILE")
                                + " passwordEnv=" + (System.getenv("BYTESTORM_REDIS_PASSWORD") != null)
                                + " args=" + String.join(",", args));
                        System.err.println("stderr-marker");
                        System.out.flush();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                        String line;
                        while ((line = reader.readLine()) != null) {
                %s
                            System.out.println("echo:" + line);
                            System.out.flush();
                        }
                    }
                }
                """.formatted(stopHandling);
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail(message);
    }
}
