package dev.bytestorm.launcher;

import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.MongoSettings;
import dev.bytestorm.core.mongo.MongoProvider;
import dev.bytestorm.launcher.wizard.GroupSetup;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Ende-zu-Ende-Start der Cloud mit echten Plattformen (§17).
 *
 * <p>Der Test fährt die Cloud hoch, lässt den ScalingManager Proxy und Lobby aus
 * den Standard-Templates starten und wartet darauf, dass sich beide Bridges per
 * Handshake melden. Damit prüft er genau den Weg, den ein Betreiber geht: Layout →
 * Templates → Plattform-Jar → Service → Bridge → {@code bs:services}.
 *
 * <p>Der Test läuft nur, wenn alles vorhanden ist: MongoDB, Redis <b>mit</b>
 * Passwort ({@code BYTESTORM_TEST_REDIS_PASSWORD}) sowie die gebauten Bridge-Jars
 * im Unterordner {@code build/libs} der Bridge-Module. Plattform-Jars lädt der Test
 * selbst über die PaperMC-API nach. Er ist damit bewusst kein schneller Unit-Test — Paper
 * benötigt beim ersten Start einige Minuten.
 */
class CloudStartupE2ETest {

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(6);

    @TempDir
    Path temp;

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void proxyAndLobbyComeOnlineFromTheStandardTemplates() throws Exception {
        requireServers();
        Path bridges = requireBridgeJars();
        Path directory = temp.resolve("cloud");
        String database = "bytestorm_e2e_" + Long.toHexString(System.nanoTime());
        writeConfig(directory, mongoUri(), database, redisAddress(), redisPassword());
        copyBridgeJars(directory, bridges);
        // Der Serverstart braucht die EULA-Zustimmung; die gibt dieser Test für seinen
        // Wegwerfordner selbst ab, die Standard-Templates enthalten sie bewusst nicht.
        Path serverLayer = directory.resolve("templates/SERVER");
        Files.createDirectories(serverLayer);
        Files.writeString(serverLayer.resolve("eula.txt"), """
                # Zustimmung nur für diesen Wegwerfordner des E2E-Tests.
                eula=true
                """);
        // Feste Gruppen statt Wizard: Der Erststart fragt sie ab, dieser Test hat kein Terminal.
        GroupSetup.create(new MongoSettings(mongoUri(), database), List.of(
                ServiceGroup.newBuilder()
                        .setName("Proxy")
                        .setType(ServiceType.PROXY)
                        .setPlatform("velocity")
                        .setMode(ServiceMode.STATIC)
                        .setMinServices(1)
                        .setMaxServices(1)
                        .setMemory(512)
                        .build(),
                ServiceGroup.newBuilder()
                        .setName("Lobby")
                        .setType(ServiceType.SERVER)
                        .setPlatform("paper")
                        .setMode(ServiceMode.DYNAMIC)
                        .setMinServices(1)
                        .setMaxServices(3)
                        .setMaxPlayers(100)
                        .setMemory(2048)
                        .setFallback(true)
                        .setFallbackPriority(1)
                        .build()));

        Bootstrap bootstrap = Bootstrap.start(directory);
        try {
            assertTrue(bootstrap.commandLine().execute("group list").contains("Lobby"),
                    "Gruppen müssen über die CLI sichtbar sein");
            ServiceInfo proxy = awaitOnline(bootstrap, "Proxy-1");
            ServiceInfo lobby = awaitOnline(bootstrap, "Lobby-1");

            assertEquals(ServiceState.ONLINE, proxy.getState());
            assertEquals(ServiceState.ONLINE, lobby.getState());
            assertEquals(25565, proxy.getPort());
            assertTrue(lobby.getPort() >= 30000, "Server-Port aus dem Pool: " + lobby.getPort());
            assertTrue(bootstrap.core().services().all().stream()
                            .anyMatch(info -> info.getName().equals("Proxy-1")
                                    && info.getState() == ServiceState.ONLINE),
                    "die Registry in Redis muss den Proxy als ONLINE führen");

            verifyPreparedDirectories(bootstrap, proxy, lobby);
            assertTrue(bootstrap.serviceController().logs(ServiceId.parse(proxy.getId())).stream()
                            .anyMatch(line -> line.contains("ByteStorm-Bridge verbunden")),
                    "das Velocity-Plugin muss die Bridge gestartet haben: "
                            + bootstrap.serviceController().logs(ServiceId.parse(proxy.getId())));
        } finally {
            bootstrap.close();
            dropDatabase(database);
        }
    }

    /**
     * Prüft, dass die Templates im Service-Ordner mit Port und Secret angekommen sind.
     */
    private static void verifyPreparedDirectories(Bootstrap bootstrap, ServiceInfo proxy, ServiceInfo lobby)
            throws IOException {
        String secret = bootstrap.config().proxy().forwardingSecret();
        assertTrue(!secret.isBlank(), "Forwarding-Secret fehlt in cloud.json");

        Path proxyDirectory = bootstrap.layout().staticServices().resolve("Proxy-1");
        String velocity = Files.readString(proxyDirectory.resolve("velocity.toml"));
        assertTrue(velocity.contains("player-info-forwarding-mode = \"modern\""), velocity);
        assertTrue(velocity.contains("bind = \"0.0.0.0:25565\""), velocity);
        assertEquals(secret, Files.readString(proxyDirectory.resolve("forwarding.secret")).trim());
        assertTrue(Files.readString(bootstrap.layout().logs().resolve("cloud.log"))
                        .contains("Forwarding-Secret"),
                "der Start muss das Secret nachtragen oder übernehmen");

        Path lobbyDirectory = dynamicDirectory(bootstrap, "Lobby-1");
        assertTrue(Files.readString(lobbyDirectory.resolve("server.properties"))
                .contains("server-port=" + lobby.getPort()));
        // Paper normalisiert seine Konfiguration beim Start und setzt dabei die
        // Anführungszeichen neu; geprüft wird deshalb der Wert, nicht die Schreibweise.
        String paper = Files.readString(lobbyDirectory.resolve("config/paper-global.yml"));
        assertTrue(paper.contains(secret), "das Secret des Proxys muss im Backend stehen: " + paper);
        assertTrue(Files.readString(proxyDirectory.resolve("velocity.toml"))
                .contains("bytestorm-placeholder"), "der Platzhalter gehört ins Template");
    }

    private static Path dynamicDirectory(Bootstrap bootstrap, String name) throws IOException {
        try (Stream<Path> entries = Files.list(bootstrap.layout().running())) {
            return entries.filter(path -> path.getFileName().toString().startsWith(name + "-"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Service-Ordner für " + name + " fehlt"));
        }
    }

    private static ServiceInfo awaitOnline(Bootstrap bootstrap, String name) throws InterruptedException {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<ServiceInfo> info = bootstrap.serviceController().byName(name);
            if (info.isPresent() && info.get().getState() == ServiceState.ONLINE) {
                return info.get();
            }
            if (info.isPresent() && switch (info.get().getState()) {
                case STOPPED, CRASHED -> true;
                default -> false;
            }) {
                // Nicht auf das Timeout warten: der Service ist gestartet und wieder beendet.
                break;
            }
            Thread.sleep(2_000);
        }
        // Der Pfad hilft beim Nachsehen; mit junit.jupiter.tempdir.cleanup.mode.default=NEVER
        // bleibt der Ordner nach dem Lauf erhalten.
        String logs = bootstrap.cloudDirectory() + System.lineSeparator()
                + bootstrap.serviceController().services().stream()
                .map(service -> service.getName() + ": " + bootstrap.serviceController()
                        .logs(ServiceId.parse(service.getId())))
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("<keine Services>");
        fail("Service '" + name + "' wurde nicht ONLINE. Services: " + logs);
        return null;
    }

    private static void copyBridgeJars(Path directory, Path bridges) throws IOException {
        Path lib = directory.resolve("lib");
        Files.createDirectories(lib);
        for (String platform : List.of("velocity", "paper")) {
            Files.copy(bridgeJar(bridges, platform), lib.resolve("bridge-" + platform + ".jar"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path requireBridgeJars() {
        Path bridges = Path.of("..", "bridge").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(bridges), "Bridge-Module nicht gefunden: " + bridges);
        for (String platform : List.of("velocity", "paper")) {
            Assumptions.assumeTrue(bridgeJar(bridges, platform) != null,
                    "lib/bridge-" + platform + ".jar fehlt — zuerst './gradlew build' ausführen");
        }
        return bridges;
    }

    private static Path bridgeJar(Path bridges, String platform) {
        Path libs = bridges.resolve("bridge-" + platform).resolve("build/libs");
        if (!Files.isDirectory(libs)) {
            return null;
        }
        try (Stream<Path> files = Files.list(libs)) {
            return files.filter(path -> path.getFileName().toString().startsWith("bridge-" + platform))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static void requireServers() {
        Assumptions.assumeTrue(reachable(mongoUri()), "MongoDB nicht erreichbar unter " + mongoUri());
        Assumptions.assumeTrue(redisAddress() != null && redisPassword() != null,
                "BYTESTORM_TEST_REDIS und BYTESTORM_TEST_REDIS_PASSWORD müssen gesetzt sein");
        Assumptions.assumeTrue(reachable(redisAddress()), "Redis nicht erreichbar unter " + redisAddress());
    }

    private static void writeConfig(Path directory, String mongoUri, String database,
                                    String redisAddress, String redisPassword) throws IOException {
        Files.createDirectories(directory.resolve("config"));
        String json = """
                {
                  "mongo": {"uri": "%s", "database": "%s"},
                  "redis": {"address": "%s", "password": "%s"},
                  "ports": {"proxy": 25565, "serverStart": 30000},
                  "scaling": {"intervalSeconds": 3},
                  "crash": {"maxRestarts": 0}
                }
                """.formatted(mongoUri, database, redisAddress, redisPassword);
        Files.writeString(directory.resolve(Bootstrap.CONFIG_FILE), json);
    }

    private static void dropDatabase(String database) {
        try (MongoProvider provider = MongoProvider.connect(new MongoSettings(mongoUri(), database))) {
            provider.database().drop();
        } catch (RuntimeException ignored) {
            // Aufräumen ist Best effort.
        }
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

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
