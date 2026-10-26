package dev.bytestorm.node.runtime;

import dev.bytestorm.api.service.ServiceHandle;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.redis.LogTopic;
import dev.bytestorm.proto.RuntimeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Startet Services als lokale Java-Prozesse (Konzept §12.2).
 *
 * <p>Der Prozess läuft im Service-Ordner, bekommt die Bridge-Jar nach
 * {@code plugins/} kopiert und die Redis-Zugangsdaten ausschließlich über die
 * Umgebung. {@code stdout} und {@code stderr} werden getrennt gelesen, gemeinsam
 * nach {@code logs/<service>/latest.log} geschrieben und in einem Ringpuffer
 * gehalten; dieselben Zeilen gehen gebatcht auf {@code bs:log:<service>}.
 *
 * <p>Konsolenbefehle laufen über {@link #sendCommand}; der Stopp-Befehl wird von
 * {@link #stop} gesendet, bevor nach Ablauf der Frist hart gekillt wird.
 */
public final class ProcessRuntime implements ServiceRuntime, ExitAware, CommandAware {

    private static final Logger log = LoggerFactory.getLogger(ProcessRuntime.class);

    /** Frist, die {@link #kill} dem Prozess zum Beenden lässt. */
    private static final Duration KILL_TIMEOUT = Duration.ofSeconds(5);

    /** Frist, in der nach dem Exit noch Restausgaben gelesen werden. */
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(2);

    private final Path logRoot;
    private final Function<String, LogPublisher> publishers;
    private final Path javaBinary;
    private final int ringSize;

    /**
     * @param logRoot Wurzel {@code logs/} der Cloud
     * @param topic   Log-Topic für {@code bs:log:<service>}
     */
    public ProcessRuntime(Path logRoot, LogTopic topic) {
        this(logRoot, name -> new BatchingLogPublisher(topic));
    }

    /**
     * @param logRoot    Wurzel {@code logs/}
     * @param publishers erzeugt je Service einen Publisher
     */
    public ProcessRuntime(Path logRoot, Function<String, LogPublisher> publishers) {
        this(logRoot, publishers, ProcessCommand.currentJava(), ServiceLogs.DEFAULT_RING_SIZE);
    }

    ProcessRuntime(Path logRoot, Function<String, LogPublisher> publishers, Path javaBinary, int ringSize) {
        this.logRoot = Objects.requireNonNull(logRoot, "logRoot").toAbsolutePath().normalize();
        this.publishers = Objects.requireNonNull(publishers, "publishers");
        this.javaBinary = Objects.requireNonNull(javaBinary, "javaBinary");
        if (ringSize <= 0) {
            throw new IllegalArgumentException("ringSize muss positiv sein: " + ringSize);
        }
        this.ringSize = ringSize;
    }

    @Override
    public ServiceHandle start(ServiceLaunch launch) {
        Objects.requireNonNull(launch, "launch");
        if (launch.options().runtime() != RuntimeType.PROCESS) {
            throw new ServiceRuntimeException(RuntimeType.PROCESS,
                    "Service '" + launch.name() + "' ist für " + launch.options().runtime() + " konfiguriert");
        }

        Path directory = launch.directory();
        Path logDirectory = logRoot.resolve(launch.name());
        LogPublisher publisher = publishers.apply(launch.name());
        ServiceLogs logs = null;
        try {
            Files.createDirectories(directory);
            BridgeInstaller.install(directory, launch.bridgeJar());
            if (!Files.isRegularFile(launch.platformJar())) {
                throw new IOException("Platform-Jar fehlt: " + launch.platformJar());
            }

            List<String> command = ProcessCommand.build(javaBinary, launch.platformJar(), launch.options());
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
            builder.environment().putAll(launch.options().environment());
            builder.environment().putAll(BridgeEnvironment.of(launch));

            logs = new ServiceLogs(launch.name(), logDirectory, ringSize, publisher);
            Process process = builder.start();
            HandleImpl handle = new HandleImpl(launch, process, logs);
            handle.watchExit();
            handle.startReaders();
            log.info("Service '{}' gestartet (PID {}, Runtime {})", launch.name(), process.pid(), RuntimeType.PROCESS);
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
            throw new ServiceRuntimeException(RuntimeType.PROCESS,
                    "Service '" + launch.name() + "' konnte nicht gestartet werden: " + e.getMessage(), e);
        }
    }

    @Override
    public void stop(ServiceHandle handle, Duration timeout) {
        HandleImpl running = require(handle);
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout muss positiv sein: " + timeout);
        }
        if (!running.process.isAlive()) {
            return;
        }

        try {
            running.sendCommand("stop");
        } catch (ServiceRuntimeException e) {
            log.debug("Stopp-Befehl für '{}' nicht zustellbar: {}", running.launch.name(), e.getMessage());
        }
        try {
            if (running.process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
            log.warn("Service '{}' hat nicht innerhalb von {} gestoppt — wird gekillt", running.launch.name(), timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        kill(running);
    }

    @Override
    public void kill(ServiceHandle handle) {
        HandleImpl running = require(handle);
        if (!running.process.isAlive()) {
            return;
        }
        running.process.destroyForcibly();
        try {
            running.process.waitFor(KILL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Service '{}' gekillt", running.launch.name());
    }

    @Override
    public boolean isAlive(ServiceHandle handle) {
        return require(handle).process.isAlive();
    }

    @Override
    public List<String> logs(ServiceHandle handle) {
        return require(handle).logs.snapshot();
    }

    @Override
    public CompletableFuture<Integer> onExit(ServiceHandle handle) {
        return require(handle).exit;
    }

    /**
     * Sendet eine Zeile an die Server-Konsole.
     *
     * @param handle  laufender Service
     * @param command Befehl ohne Zeilenende
     * @throws ServiceRuntimeException wenn der Prozess nicht mehr läuft
     */
    @Override
    public void sendCommand(ServiceHandle handle, String command) {
        require(handle).sendCommand(command);
    }

    private HandleImpl require(ServiceHandle handle) {
        if (!(handle instanceof ProcessRuntime.HandleImpl running)) {
            throw new IllegalArgumentException("Handle gehört nicht zur ProcessRuntime: " + handle);
        }
        return running;
    }

    private final class HandleImpl implements ServiceHandle {

        private final ServiceLaunch launch;
        private final Process process;
        private final ServiceLogs logs;
        private final BufferedWriter stdin;
        private final CountDownLatch readers = new CountDownLatch(2);
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private boolean stdinClosed;

        private HandleImpl(ServiceLaunch launch, Process process, ServiceLogs logs) {
            this.launch = launch;
            this.process = process;
            this.logs = logs;
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }

        private void startReaders() {
            startReader(process.getInputStream(), "OUT");
            startReader(process.getErrorStream(), "ERR");
        }

        private void startReader(InputStream stream, String channel) {
            Thread.ofVirtual()
                    .name("bytestorm-" + launch.name() + "-" + channel.toLowerCase())
                    .start(() -> pump(stream, channel));
        }

        private void pump(InputStream stream, String channel) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logs.accept(channel, line);
                }
            } catch (IOException | RuntimeException e) {
                if (process.isAlive()) {
                    log.warn("Logstrom {} von '{}' abgebrochen: {}", channel, launch.name(), e.toString());
                }
            } finally {
                readers.countDown();
            }
        }

        private void watchExit() {
            Thread.ofVirtual()
                    .name("bytestorm-" + launch.name() + "-reaper")
                    .start(() -> {
                        process.onExit().join();
                        awaitReaders();
                        logs.close();
                        closeStdin();
                        launch.port().close();
                        int exitCode = process.exitValue();
                        exit.complete(exitCode);
                        log.info("Service '{}' beendet (Exit {})", launch.name(), exitCode);
                    });
        }

        private void awaitReaders() {
            try {
                if (!readers.await(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("Restausgaben von '{}' nicht vollständig gelesen", launch.name());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private synchronized void sendCommand(String command) {
            Objects.requireNonNull(command, "command");
            if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Konsolenbefehl darf kein Zeilenende enthalten: " + command);
            }
            if (!process.isAlive()) {
                throw new ServiceRuntimeException(RuntimeType.PROCESS,
                        "Service '" + launch.name() + "' läuft nicht mehr");
            }
            try {
                stdin.write(command);
                stdin.write(System.lineSeparator());
                stdin.flush();
            } catch (IOException e) {
                throw new ServiceRuntimeException(RuntimeType.PROCESS,
                        "Konsole von '" + launch.name() + "' nicht erreichbar: " + e.getMessage(), e);
            }
        }

        private synchronized void closeStdin() {
            if (stdinClosed) {
                return;
            }
            stdinClosed = true;
            try {
                stdin.close();
            } catch (IOException e) {
                // Prozess ist beendet; der Writer ist dann ohnehin unbrauchbar.
            }
        }

        @Override
        public ServiceId id() {
            return launch.id();
        }

        @Override
        public String nativeId() {
            return Long.toString(process.pid());
        }

        @Override
        public Path directory() {
            return launch.directory();
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }
    }
}
