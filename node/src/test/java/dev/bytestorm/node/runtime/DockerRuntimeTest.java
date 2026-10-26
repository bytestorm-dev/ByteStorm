package dev.bytestorm.node.runtime;

import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.api.service.ServiceHandle;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.proto.LogLine;
import dev.bytestorm.proto.RuntimeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerRuntimeTest {

    @TempDir
    Path temp;

    private final FakeEngine engine = new FakeEngine();
    private final AtomicBoolean publisherClosed = new AtomicBoolean();
    private final List<LogLine> published = Collections.synchronizedList(new ArrayList<>());

    @Test
    void buildsTheContainerRequestAndInstallsTheBridge() throws Exception {
        Path work = Files.createDirectories(temp.resolve("work"));
        Path jar = Files.writeString(work.resolve("server.jar"), "jar");
        Path bridge = Files.writeString(work.resolve("ByteStormBridge.jar"), "bridge");
        DockerRuntime runtime = runtime();

        ServiceLaunch launch = launch(work, jar, bridge, RuntimeType.DOCKER);
        ServiceHandle handle = runtime.start(launch);

        DockerContainerRequest request = engine.request;
        assertEquals(DockerRuntime.DEFAULT_IMAGE, request.image());
        assertEquals(DockerRuntime.DATA_DIRECTORY, request.workingDirectory());
        assertEquals(work.resolve("service").toAbsolutePath().normalize(), request.dataDirectory());
        assertEquals(30001, request.port());
        assertEquals(List.of("java", "-Xms1024M", "-Xmx1024M", "-XX:+UseG1GC",
                "-jar", "/data/server.jar", "nogui"), request.command());
        assertEquals("Lobby-1", request.environment().get(BridgeEnvironment.SERVICE_NAME));
        assertEquals("secret", request.environment().get(BridgeEnvironment.REDIS_PASSWORD));
        assertEquals("custom", request.environment().get("EXTRA"));

        assertTrue(Files.isRegularFile(work.resolve("service/plugins/ByteStormBridge.jar")));
        assertEquals("container-1", handle.nativeId());
        assertTrue(handle.isAlive());
        assertFalse(launch.port().released());
    }

    @Test
    void assemblesFramesIntoLinesAndClosesThePublisherOnEnd() throws Exception {
        Path work = Files.createDirectories(temp.resolve("work"));
        Path jar = Files.writeString(work.resolve("server.jar"), "jar");
        DockerRuntime runtime = runtime();
        ServiceLaunch launch = launch(work, jar, null, RuntimeType.DOCKER);
        ServiceHandle handle = runtime.start(launch);

        engine.handler.frame(false, "hel".getBytes(StandardCharsets.UTF_8));
        engine.handler.frame(false, "lo\r\n".getBytes(StandardCharsets.UTF_8));
        engine.handler.frame(true, "err".getBytes(StandardCharsets.UTF_8));
        engine.handler.frame(true, "or\n".getBytes(StandardCharsets.UTF_8));
        engine.handler.frame(false, "tail".getBytes(StandardCharsets.UTF_8));
        engine.handler.end();

        assertTrue(runtime.logs(handle).stream().anyMatch(line -> line.contains("[OUT] hello")));
        assertTrue(runtime.logs(handle).stream().anyMatch(line -> line.contains("[ERR] error")));
        assertTrue(runtime.logs(handle).stream().anyMatch(line -> line.contains("[OUT] tail")));
        assertTrue(Files.readString(temp.resolve("logs/Lobby-1/latest.log")).contains("hello"));
        assertTrue(publisherClosed.get());
        assertTrue(launch.port().released());
        assertEquals(-1, ((ExitAware) runtime).onExit(handle).get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(published.isEmpty());
    }

    @Test
    void handlesUtf8SplitAcrossFrames() throws Exception {
        Path work = Files.createDirectories(temp.resolve("work"));
        Path jar = Files.writeString(work.resolve("server.jar"), "jar");
        DockerRuntime runtime = runtime();
        ServiceHandle handle = runtime.start(launch(work, jar, null, RuntimeType.DOCKER));

        byte[] bytes = "grüß\n".getBytes(StandardCharsets.UTF_8);
        engine.handler.frame(false, new byte[]{bytes[0], bytes[1]});
        engine.handler.frame(false, java.util.Arrays.copyOfRange(bytes, 2, bytes.length));
        engine.handler.end();

        assertTrue(runtime.logs(handle).stream().anyMatch(line -> line.contains("grüß")));
    }

    @Test
    void stopUsesTheGracefulTimeoutAndKillIsImmediate() throws Exception {
        Path work = Files.createDirectories(temp.resolve("work"));
        Path jar = Files.writeString(work.resolve("server.jar"), "jar");
        DockerRuntime runtime = runtime();
        ServiceLaunch launch = launch(work, jar, null, RuntimeType.DOCKER);
        ServiceHandle handle = runtime.start(launch);

        runtime.stop(handle, Duration.ofSeconds(7));

        assertTrue(engine.stopped);
        assertEquals(Duration.ofSeconds(7), engine.stopTimeout);
        assertFalse(handle.isAlive());
        assertTrue(publisherClosed.get());
        assertTrue(launch.port().released());
        assertEquals(-1, ((ExitAware) runtime).onExit(handle).get(5, java.util.concurrent.TimeUnit.SECONDS));

        engine.running = true;
        runtime.kill(handle);
        assertTrue(engine.killed);
        assertFalse(handle.isAlive());
    }

    @Test
    void failsWhenThePlatformJarIsMissing() throws Exception {
        Path work = Files.createDirectories(temp.resolve("work"));
        Files.createDirectories(work);
        DockerRuntime runtime = runtime();

        ServiceRuntimeException error = assertThrows(ServiceRuntimeException.class,
                () -> runtime.start(launch(work, work.resolve("missing.jar"), null, RuntimeType.DOCKER)));

        assertTrue(error.getMessage().contains("Platform-Jar fehlt"), error.getMessage());
    }

    @Test
    void rejectsAProcessService() throws Exception {
        Path work = Files.createDirectories(temp.resolve("work"));
        Path jar = Files.writeString(work.resolve("server.jar"), "jar");
        DockerRuntime runtime = runtime();

        ServiceRuntimeException error = assertThrows(ServiceRuntimeException.class,
                () -> runtime.start(launch(work, jar, null, RuntimeType.PROCESS)));

        assertTrue(error.getMessage().contains("PROCESS"), error.getMessage());
    }

    @Test
    void closeClosesTheEngine() {
        runtime().close();

        assertTrue(engine.closed);
    }

    private DockerRuntime runtime() {
        return new DockerRuntime(temp.resolve("logs"), name -> {
            return new LogPublisher() {
                @Override
                public void publish(LogLine line) {
                    published.add(line);
                }

                @Override
                public void close() {
                    publisherClosed.set(true);
                }
            };
        }, engine, DockerRuntime.DEFAULT_IMAGE, 100);
    }

    private ServiceLaunch launch(Path work, Path platformJar, Path bridgeJar, RuntimeType runtime) {
        return new ServiceLaunch(ServiceId.random(), "Lobby-1", PortLease.fixed(30001), work.resolve("service"),
                platformJar, bridgeJar,
                LaunchOptions.builder()
                        .memoryMb(1024)
                        .jvmArg("-XX:+UseG1GC")
                        .environment("EXTRA", "custom")
                        .runtime(runtime)
                        .build(),
                new RedisSettings("redis://localhost:6379", "secret"));
    }

    private static final class FakeEngine implements DockerEngine {

        private DockerContainerRequest request;
        private DockerLogHandler handler;
        private boolean running;
        private boolean stopped;
        private boolean killed;
        private boolean closed;
        private Duration stopTimeout;

        @Override
        public String start(DockerContainerRequest request) {
            this.request = request;
            this.running = true;
            return "container-1";
        }

        @Override
        public void stop(String containerId, Duration timeout) {
            stopped = true;
            stopTimeout = timeout;
            running = false;
            handler.end();
        }

        @Override
        public void kill(String containerId) {
            killed = true;
            running = false;
            handler.end();
        }

        @Override
        public boolean isRunning(String containerId) {
            return running;
        }

        @Override
        public Closeable streamLogs(String containerId, DockerLogHandler handler) {
            this.handler = handler;
            return () -> {
            };
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
