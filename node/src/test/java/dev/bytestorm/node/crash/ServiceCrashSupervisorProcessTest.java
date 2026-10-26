package dev.bytestorm.node.crash;

import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.CrashSettings;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.node.lifecycle.DirectoryPreparer;
import dev.bytestorm.node.lifecycle.ServiceDefinition;
import dev.bytestorm.node.lifecycle.ServiceInstance;
import dev.bytestorm.node.runtime.PortLease;
import dev.bytestorm.node.runtime.ProcessRuntime;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceCrashed;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceMode;
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
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ServiceCrashSupervisorProcessTest {

    private static final String MAIN_CLASS = "dev.bytestorm.testchild.CrashingServer";

    @TempDir
    Path temp;

    private final List<ServiceCrashed> events = Collections.synchronizedList(new ArrayList<>());
    private final List<String> degraded = Collections.synchronizedList(new ArrayList<>());

    @Test
    void aRealCrashIsReportedAndRestartedUntilTheGroupIsDegraded() throws Exception {
        Path work = Files.createDirectories(temp.resolve("work"));
        Path jar = createCrashingJar(work);
        ServiceGroup group = ServiceGroup.newBuilder()
                .setName("Lobby")
                .setMode(ServiceMode.DYNAMIC)
                .setMaxPlayers(100)
                .build();
        ServiceDefinition definition = new ServiceDefinition(ServiceId.random(), "Lobby-1", group,
                temp.resolve("service"), jar, null,
                LaunchOptions.builder().memoryMb(64).runtime(RuntimeType.PROCESS).build(),
                new RedisSettings("redis://localhost:6379", "secret"));

        ProcessRuntime runtime = new ProcessRuntime(temp.resolve("logs"), name -> line -> {
        });
        ServiceInstance instance = new ServiceInstance(definition, runtime,
                () -> PortLease.fixed(30001), new DirectoryPreparer(), event -> {
        });
        ServiceCrashSupervisor supervisor = new ServiceCrashSupervisor(instance,
                new CrashSettings(1, List.of(0)),
                new CrashReportWriter(temp.resolve("crash-reports")),
                events::add, CrashHandler.virtualThreads(), degraded::add);

        supervisor.start();

        await(() -> degraded.size() == 1, "Gruppe wurde nach dem zweiten Crash nicht degradiert");
        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(event -> event.getExitCode() == 1));
        assertTrue(events.stream().allMatch(event -> Files.isDirectory(Path.of(event.getReportPath()))));
        assertTrue(events.get(0).getLogTailList().stream().anyMatch(line -> line.contains("crash")),
                "Log-Tail fehlt: " + events.get(0).getLogTailList());
    }

    private Path createCrashingJar(Path work) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null, "Test-JVM enthält keinen Java-Compiler");

        Path source = work.resolve("src/" + MAIN_CLASS.replace('.', '/') + ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package dev.bytestorm.testchild;

                public final class CrashingServer {
                    public static void main(String[] args) {
                        System.out.println("crash marker before exit");
                        System.out.flush();
                        System.exit(1);
                    }
                }
                """);
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

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail(message);
    }
}
