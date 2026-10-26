package dev.bytestorm.node.runtime;

import dev.bytestorm.api.service.ServiceHandle;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.redis.LogTopic;
import dev.bytestorm.proto.RuntimeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Startet Services in vorhandenen Docker-Images (Konzept §12.3).
 *
 * <p>Der Service-Ordner wird als Volume auf {@code /data} gebunden, der
 * Service-Port wird eins zu eins gemappt, und die Bridge-Jar liegt wie bei
 * {@link ProcessRuntime} unter {@code plugins/}. Es wird bewusst kein eigenes
 * Image gebaut und keine Ressourcenlimits gesetzt (Entscheidung §12.3, Risiko
 * §23). Logs laufen über denselben Pfad wie bei der Process-Runtime:
 * Ringpuffer, {@code logs/<service>/latest.log} und gebatchtes
 * {@code bs:log:<service>}.
 */
public final class DockerRuntime implements ServiceRuntime, ExitAware, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DockerRuntime.class);

    /** Vorhandenes Image; Java ist enthalten, die Platform-Jar kommt aus dem Volume. */
    public static final String DEFAULT_IMAGE = "eclipse-temurin:26-jre";

    /** Mountpunkt des Service-Ordners. */
    public static final String DATA_DIRECTORY = "/data";

    private final Path logRoot;
    private final Function<String, LogPublisher> publishers;
    private final DockerEngine engine;
    private final String image;
    private final int ringSize;

    /**
     * @param logRoot    Wurzel {@code logs/}
     * @param topic      Log-Topic
     * @param dockerHost Docker-Host aus {@code cloud.json}
     */
    public DockerRuntime(Path logRoot, LogTopic topic, String dockerHost) {
        this(logRoot, name -> new BatchingLogPublisher(topic),
                new DockerJavaEngine(dockerHost), DEFAULT_IMAGE, ServiceLogs.DEFAULT_RING_SIZE);
    }

    DockerRuntime(Path logRoot,
                  Function<String, LogPublisher> publishers,
                  DockerEngine engine,
                  String image,
                  int ringSize) {
        this.logRoot = Objects.requireNonNull(logRoot, "logRoot").toAbsolutePath().normalize();
        this.publishers = Objects.requireNonNull(publishers, "publishers");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.image = Objects.requireNonNull(image, "image");
        if (ringSize <= 0) {
            throw new IllegalArgumentException("ringSize muss positiv sein: " + ringSize);
        }
        this.ringSize = ringSize;
    }

    @Override
    public ServiceHandle start(ServiceLaunch launch) {
        Objects.requireNonNull(launch, "launch");
        if (launch.options().runtime() != RuntimeType.DOCKER) {
            throw new ServiceRuntimeException(RuntimeType.DOCKER,
                    "Service '" + launch.name() + "' ist für " + launch.options().runtime() + " konfiguriert");
        }

        LogPublisher publisher = publishers.apply(launch.name());
        ServiceLogs logs = null;
        try {
            Files.createDirectories(launch.directory());
            BridgeInstaller.install(launch.directory(), launch.bridgeJar());
            if (!Files.isRegularFile(launch.platformJar())) {
                throw new IOException("Platform-Jar fehlt: " + launch.platformJar());
            }

            Path containerJar = Path.of(DATA_DIRECTORY, launch.platformJar().getFileName().toString());
            List<String> command = ProcessCommand.build(Path.of("java"), containerJar, launch.options());
            Map<String, String> environment = new LinkedHashMap<>(launch.options().environment());
            environment.putAll(BridgeEnvironment.of(launch));

            logs = new ServiceLogs(launch.name(), logRoot.resolve(launch.name()), ringSize, publisher);
            DockerContainerRequest request = new DockerContainerRequest(image, command, DATA_DIRECTORY,
                    environment, launch.directory(), launch.port().port());
            String containerId = engine.start(request);
            HandleImpl handle = new HandleImpl(launch, containerId, logs);
            try {
                handle.startLogs();
            } catch (RuntimeException e) {
                engine.kill(containerId);
                logs.close();
                throw e;
            }
            log.info("Service '{}' als Container {} gestartet (Image {})", launch.name(), containerId, image);
            return handle;
        } catch (IOException | RuntimeException e) {
            if (logs != null) {
                logs.close();
            } else {
                publisher.close();
            }
            if (e instanceof ServiceRuntimeException failure) {
                throw failure;
            }
            throw new ServiceRuntimeException(RuntimeType.DOCKER,
                    "Service '" + launch.name() + "' konnte nicht als Container gestartet werden: " + e.getMessage(), e);
        }
    }

    @Override
    public void stop(ServiceHandle handle, Duration timeout) {
        HandleImpl running = require(handle);
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout muss positiv sein: " + timeout);
        }
        if (!isRunning(running.containerId)) {
            running.launch.port().close();
            return;
        }
        engine.stop(running.containerId, timeout);
        running.launch.port().close();
        running.completeExit();
    }

    @Override
    public void kill(ServiceHandle handle) {
        HandleImpl running = require(handle);
        if (!isRunning(running.containerId)) {
            running.launch.port().close();
            return;
        }
        engine.kill(running.containerId);
        running.launch.port().close();
        running.completeExit();
    }

    @Override
    public boolean isAlive(ServiceHandle handle) {
        return isRunning(require(handle).containerId);
    }

    @Override
    public List<String> logs(ServiceHandle handle) {
        return require(handle).logs.snapshot();
    }

    @Override
    public CompletableFuture<Integer> onExit(ServiceHandle handle) {
        return require(handle).exit;
    }

    @Override
    public void close() {
        engine.close();
    }

    private boolean isRunning(String containerId) {
        try {
            return engine.isRunning(containerId);
        } catch (RuntimeException e) {
            log.debug("Container-Status von {} nicht abfragbar: {}", containerId, e.toString());
            return false;
        }
    }

    private HandleImpl require(ServiceHandle handle) {
        if (!(handle instanceof DockerRuntime.HandleImpl running)) {
            throw new IllegalArgumentException("Handle gehört nicht zur DockerRuntime: " + handle);
        }
        return running;
    }

    private final class HandleImpl implements ServiceHandle, DockerLogHandler {

        private final ServiceLaunch launch;
        private final String containerId;
        private final ServiceLogs logs;
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private boolean ended;
        private Closeable stream;

        private HandleImpl(ServiceLaunch launch, String containerId, ServiceLogs logs) {
            this.launch = launch;
            this.containerId = containerId;
            this.logs = logs;
        }

        private void startLogs() {
            stream = engine.streamLogs(containerId, this);
        }

        @Override
        public synchronized void frame(boolean error, byte[] payload) {
            if (ended || payload == null || payload.length == 0) {
                return;
            }
            ByteArrayOutputStream buffer = error ? stderr : stdout;
            buffer.write(payload, 0, payload.length);
            byte[] data = buffer.toByteArray();
            int start = 0;
            for (int i = 0; i < data.length; i++) {
                if (data[i] == '\n') {
                    emit(error, data, start, i);
                    start = i + 1;
                }
            }
            buffer.reset();
            if (start < data.length) {
                buffer.write(data, start, data.length - start);
            }
        }

        @Override
        public void end() {
            synchronized (this) {
                if (ended) {
                    return;
                }
                ended = true;
                emitRemainder(false, stdout);
                emitRemainder(true, stderr);
            }
            logs.close();
            launch.port().close();
            completeExit();
            closeQuietly(stream);
        }

        private void completeExit() {
            // Docker liefert nach dem Auto-Remove keinen Exit-Code mehr.
            exit.complete(-1);
        }

        private void emitRemainder(boolean error, ByteArrayOutputStream buffer) {
            byte[] data = buffer.toByteArray();
            if (data.length > 0) {
                emit(error, data, 0, data.length);
                buffer.reset();
            }
        }

        private void emit(boolean error, byte[] data, int start, int end) {
            int length = end - start;
            if (length > 0 && data[end - 1] == '\r') {
                length--;
            }
            String line = new String(data, start, length, StandardCharsets.UTF_8);
            logs.accept(error ? "ERR" : "OUT", line);
        }

        private void closeQuietly(Closeable closeable) {
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            } catch (IOException e) {
                // Stream ist beendet; nichts mehr aufzuräumen.
            }
        }

        @Override
        public ServiceId id() {
            return launch.id();
        }

        @Override
        public String nativeId() {
            return containerId;
        }

        @Override
        public Path directory() {
            return launch.directory();
        }

        @Override
        public boolean isAlive() {
            return isRunning(containerId);
        }
    }
}
