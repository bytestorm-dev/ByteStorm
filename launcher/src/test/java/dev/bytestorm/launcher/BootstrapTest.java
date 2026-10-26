package dev.bytestorm.launcher;

import dev.bytestorm.api.event.Subscription;
import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.controller.ServiceController;
import dev.bytestorm.core.config.ConfigException;
import dev.bytestorm.core.config.MongoSettings;
import dev.bytestorm.core.health.Healthcheck;
import dev.bytestorm.core.mongo.MongoProvider;
import dev.bytestorm.launcher.layout.CloudLayout;
import dev.bytestorm.node.lifecycle.ServiceDefinition;
import dev.bytestorm.proto.Heartbeat;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests für den Bootstrap.
 *
 * <p>Die Ende-zu-Ende-Tests laufen nur mit echten Servern: MongoDB aus
 * {@code BYTESTORM_TEST_MONGO} (Default {@code mongodb://localhost:27017}, es
 * wird eine zufällige Datenbank benutzt) und Redis aus
 * {@code BYTESTORM_TEST_REDIS} <b>mit</b> {@code BYTESTORM_TEST_REDIS_PASSWORD},
 * weil die Konfiguration ein Redis-Passwort verlangt (§20). Ohne diese Variablen
 * werden sie übersprungen.
 */
class BootstrapTest {

    @TempDir
    Path temp;

    @Test
    void failsWithoutConfigFile() {
        Path directory = temp.resolve("empty");

        ConfigException error = assertThrows(ConfigException.class, () -> Bootstrap.start(directory));

        assertTrue(error.getMessage().contains("cloud.json"), error.getMessage());
        for (String name : CloudLayout.DIRECTORIES) {
            assertTrue(Files.isDirectory(directory.resolve(name)), "Layout-Verzeichnis fehlt: " + name);
        }
    }

    @Test
    void missingConfigWithoutTerminalExplainsTheWizardRequirement() {
        Path directory = temp.resolve("wizardless");

        ConfigException error = assertThrows(ConfigException.class,
                () -> Bootstrap.ensureConfigured(directory));

        assertTrue(error.getMessage().contains("Erststart-Wizard"), error.getMessage());
        assertTrue(error.getMessage().contains("Terminal"), error.getMessage());
        assertFalse(Files.exists(directory.resolve(Bootstrap.CONFIG_FILE)));
    }

    @Test
    void failsOnInvalidConfigBeforeOpeningConnections() throws IOException {
        Path directory = temp.resolve("invalid");
        writeConfig(directory, mongoUri(), uniqueDatabase(), "redis://127.0.0.1:6379", null);

        ConfigException error = assertThrows(ConfigException.class, () -> Bootstrap.start(directory));

        assertTrue(error.getMessage().contains("redis.password"), error.getMessage());
    }

    @Test
    void startsAndStopsMongoAndRedis() throws Exception {
        requireServers();
        Path directory = temp.resolve("cloud");
        String database = uniqueDatabase();
        writeConfig(directory, mongoUri(), database, redisAddress(), redisPassword());

        Bootstrap bootstrap = Bootstrap.start(directory);
        try {
            assertTrue(bootstrap.healthcheck().check().up());
            assertEquals(List.of(Healthcheck.MONGO, Healthcheck.REDIS, Healthcheck.RUNTIME),
                    List.copyOf(bootstrap.healthcheck().check().components().keySet()));
            assertEquals(database, bootstrap.config().mongo().database());
            assertNotNull(bootstrap.logFile());
            assertTrue(Files.isDirectory(bootstrap.layout().templates()));
            assertTrue(Files.isDirectory(bootstrap.layout().running()));
            assertNotNull(bootstrap.serviceManager());
            assertNotNull(bootstrap.serviceController());
            assertNotNull(bootstrap.scalingManager());
            assertTrue(bootstrap.commandLine().execute("service list").contains("Keine Services"),
                    "CLI muss den leeren ServiceManager sehen");

            CountDownLatch eventReceived = new CountDownLatch(1);
            Subscription subscription = bootstrap.core().events()
                    .subscribe(Heartbeat.class, event -> eventReceived.countDown());
            assertTrue(subscription.isActive());
            assertEquals(1, bootstrap.core().events().subscriptionCount());
            bootstrap.core().events().publish(Heartbeat.getDefaultInstance());
            assertTrue(eventReceived.await(10, TimeUnit.SECONDS), "Event kam nicht an");

            bootstrap.core().rpc().register(PingRpc.class, () -> "pong");
            try {
                assertEquals("pong", bootstrap.core().rpc()
                        .client(PingRpc.class, Duration.ofSeconds(5)).ping());
            } finally {
                bootstrap.core().rpc().deregister(PingRpc.class);
            }

            bootstrap.redis().client().getKeys().deleteByPattern("bs:*");
        } finally {
            bootstrap.mongo().database().drop();
            bootstrap.close();
        }

        bootstrap.close();
        assertFalse(bootstrap.healthcheck().check().up(), "geschlossene Provider dürfen nicht mehr gesund sein");
        assertEquals(0, bootstrap.core().events().subscriptionCount(), "Event-Bus wurde nicht geschlossen");
    }

    @Test
    void startsRealServiceAndReadsLogsThroughController() throws Exception {
        requireServers();
        Path directory = temp.resolve("service-run");
        String database = uniqueDatabase();
        writeConfig(directory, mongoUri(), database, redisAddress(), redisPassword());
        Path platformJar = temp.resolve("lifecycle-probe.jar");
        jarMainClass(platformJar, LifecycleProbe.class);

        Bootstrap bootstrap = Bootstrap.start(directory);
        try {
            ServiceId id = ServiceId.random();
            ServiceGroup group = ServiceGroup.newBuilder()
                    .setName("Probe")
                    .setType(ServiceType.SERVER)
                    .setMode(ServiceMode.DYNAMIC)
                    .setMaxPlayers(10)
                    .setMemory(64)
                    .build();
            ServiceDefinition definition = new ServiceDefinition(id, "Probe-1", group,
                    bootstrap.layout().running().resolve("Probe-" + id), platformJar, null,
                    LaunchOptions.builder().memoryMb(64).build(), bootstrap.config().redis());

            ServiceInfo started = bootstrap.serviceController().start(definition);
            assertEquals(ServiceState.STARTING, started.getState());
            awaitLog(bootstrap.serviceController(), id, "lifecycle-probe ready", Duration.ofSeconds(20));
            assertTrue(bootstrap.commandLine().execute("service logs Probe-1")
                    .contains("lifecycle-probe ready"));

            ServiceInfo stopped = bootstrap.serviceController().stop(id, Duration.ofSeconds(5), false);
            assertEquals(ServiceState.STOPPED, stopped.getState());
            assertTrue(bootstrap.serviceController().logs(id).stream()
                    .anyMatch(line -> line.contains("lifecycle-probe ready")));
        } finally {
            bootstrap.close();
            dropDatabase(database);
        }
    }

    @Test
    void mainProcessShutsDownGracefullyOnSigterm() throws Exception {
        requireServers();
        Path directory = temp.resolve("process");
        String database = uniqueDatabase();
        writeConfig(directory, mongoUri(), database, redisAddress(), redisPassword());
        Path stdout = directory.resolve("stdout.log");
        // Der Bootstrap wird aus lib/ geladen, nicht aus dem Classpath des Kindprozesses.
        jarClass(directory.resolve("lib"), "bootstrap.jar", Bootstrap.class);

        Process process = new ProcessBuilder(javaBinary(), "-cp", System.getProperty("java.class.path"),
                Launcher.class.getName(), "--dir", directory.toString())
                .redirectErrorStream(true)
                .redirectOutput(stdout.toFile())
                .start();
        try {
            Path log = directory.resolve("logs").resolve("cloud.log");
            awaitLog(log, stdout, process, "ByteStorm bereit", Duration.ofSeconds(30));

            process.destroy();

            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Prozess reagiert nicht auf SIGTERM");
            String content = Files.readString(log);
            assertTrue(content.contains("Healthcheck mongo: OK"), content);
            assertTrue(content.contains("Healthcheck redis: OK"), content);
            assertTrue(content.contains("Event-Bus und RPC bereit"), content);
            assertTrue(content.contains("Fahre Cloud herunter (6 Schritte)"), content);
            assertTrue(content.contains("Shutdown abgeschlossen"), content);
        } finally {
            process.destroyForcibly();
            dropDatabase(database);
        }
    }

    private static void awaitLog(Path log, Path stdout, Process process, String expected, Duration timeout)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(log) && Files.readString(log).contains(expected)) {
                return;
            }
            if (!process.isAlive()) {
                fail("Prozess vorzeitig beendet (Exit " + process.exitValue() + "): " + stdoutText(stdout));
            }
            Thread.sleep(100);
        }
        fail("Logzeile '" + expected + "' fehlt. Log: "
                + (Files.exists(log) ? Files.readString(log) : "<keine Logdatei>")
                + " Ausgabe: " + stdoutText(stdout));
    }

    private static String stdoutText(Path stdout) throws IOException {
        return Files.exists(stdout) ? Files.readString(stdout) : "<keine Ausgabe>";
    }

    private static void requireServers() {
        Assumptions.assumeTrue(reachable(mongoUri()), "MongoDB nicht erreichbar unter " + mongoUri());
        Assumptions.assumeTrue(redisAddress() != null, "BYTESTORM_TEST_REDIS nicht gesetzt");
        Assumptions.assumeTrue(redisPassword() != null,
                "BYTESTORM_TEST_REDIS_PASSWORD nicht gesetzt — Konfiguration verlangt ein Redis-Passwort");
        Assumptions.assumeTrue(reachable(redisAddress()), "Redis nicht erreichbar unter " + redisAddress());
    }

    private static void awaitLog(ServiceController controller, ServiceId id, String expected, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (controller.logs(id).stream().anyMatch(line -> line.contains(expected))) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Logzeile '" + expected + "' fehlt. Bisher: " + controller.logs(id));
    }

    private static void jarClass(Path libDirectory, String jarName, Class<?> type) throws IOException {
        String entryName = type.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = type.getClassLoader().getResourceAsStream(entryName)) {
            assertNotNull(in, "Klassendatei fehlt im Test-Classpath: " + entryName);
            bytes = in.readAllBytes();
        }
        Files.createDirectories(libDirectory);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(libDirectory.resolve(jarName)))) {
            out.putNextEntry(new JarEntry(entryName));
            out.write(bytes);
            out.closeEntry();
        }
    }

    private static void jarMainClass(Path jar, Class<?> mainClass) throws IOException {
        String entryName = mainClass.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = mainClass.getClassLoader().getResourceAsStream(entryName)) {
            assertNotNull(in, "Klassendatei fehlt im Test-Classpath: " + entryName);
            bytes = in.readAllBytes();
        }
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            out.write(("Manifest-Version: 1.0\r\nMain-Class: " + mainClass.getName() + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry(entryName));
            out.write(bytes);
            out.closeEntry();
        }
    }

    private static void writeConfig(Path directory, String mongoUri, String database,
                                    String redisAddress, String redisPassword) throws IOException {
        Files.createDirectories(directory.resolve("config"));
        String password = redisPassword == null ? "null" : "\"" + redisPassword + "\"";
        String json = """
                {
                  "mongo": {"uri": "%s", "database": "%s"},
                  "redis": {"address": "%s", "password": %s}
                }
                """.formatted(mongoUri, database, redisAddress, password);
        Files.writeString(directory.resolve(Bootstrap.CONFIG_FILE), json);
    }

    private static void dropDatabase(String database) {
        try (MongoProvider provider = MongoProvider.connect(new MongoSettings(mongoUri(), database))) {
            provider.database().drop();
        } catch (RuntimeException ignored) {
            // Aufräumen ist Best effort; der Testlauf ist bereits beendet.
        }
    }

    private static String javaBinary() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path java = Files.isExecutable(bin.resolve("java")) ? bin.resolve("java") : bin.resolve("java.exe");
        return java.toString();
    }

    private static boolean reachable(String address) {
        URI uri;
        try {
            uri = URI.create(address.contains("://") ? address : "redis://" + address);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (uri.getHost() == null) {
            return false;
        }
        int port = uri.getPort() < 0 ? 6379 : uri.getPort();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), port), 1_000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String mongoUri() {
        return env("BYTESTORM_TEST_MONGO", "mongodb://localhost:27017");
    }

    private static String redisAddress() {
        return env("BYTESTORM_TEST_REDIS", null);
    }

    private static String redisPassword() {
        return env("BYTESTORM_TEST_REDIS_PASSWORD", null);
    }

    private static String uniqueDatabase() {
        return "bytestorm_launcher_" + Long.toHexString(System.nanoTime());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
