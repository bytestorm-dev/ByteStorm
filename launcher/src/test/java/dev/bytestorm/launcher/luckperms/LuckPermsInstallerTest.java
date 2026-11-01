package dev.bytestorm.launcher.luckperms;

import dev.bytestorm.core.config.MongoSettings;
import dev.bytestorm.core.config.RedisSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuckPermsInstallerTest {

    private static final byte[] BUKKIT_JAR = "fake-luckperms-bukkit".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VELOCITY_JAR = "fake-luckperms-velocity".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FABRIC_JAR = "fake-luckperms-fabric".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temp;

    private final AtomicInteger metadataHits = new AtomicInteger();
    private final AtomicInteger downloads = new AtomicInteger();

    private com.sun.net.httpserver.HttpServer server;
    private LuckPermsInstaller installer;

    @BeforeEach
    void startServer() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        installer = new LuckPermsInstaller(temp.resolve("platforms").resolve("luckperms"),
                URI.create(base()), java.net.http.HttpClient.newHttpClient(),
                LuckPermsInstaller.USER_AGENT);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void installsTheBukkitJarAndWiresMongoAndRedis() throws Exception {
        Path service = temp.resolve("running").resolve("Lobby-1");

        Path jar = installer.install(group("Lobby", "paper"), service,
                new MongoSettings("mongodb://lp:geheim@db.example:27017/bytestorm", null),
                new RedisSettings("redis://:rot@cache.example:6380", null)).orElseThrow();

        assertEquals(service.resolve("plugins").resolve("luckperms-bukkit.jar"), jar);
        assertTrue(Files.isRegularFile(service.resolve("plugins/LuckPerms/config.yml")));

        String config = Files.readString(service.resolve("plugins/LuckPerms/config.yml"));
        assertTrue(config.contains("storage-method: mongodb"), config);
        assertTrue(config.contains("mongodb-connection-uri: 'mongodb://lp:geheim@db.example:27017/bytestorm'"), config);
        assertTrue(config.contains("address: 'db.example:27017'"),
                "LuckPerms braucht host:port: " + config);
        assertTrue(config.contains("database: 'bytestorm'"), config);
        assertTrue(config.contains("username: 'lp'"), config);
        assertTrue(config.contains("password: 'geheim'"), config);
        assertTrue(config.contains("mongodb-collection-prefix: '" + LuckPermsInstaller.COLLECTION_PREFIX + "'"),
                "ohne Prefix überschreibt LuckPerms die groups-Collection der Cloud");
        assertTrue(config.contains("messaging-service: redis"), config);
        assertTrue(config.contains("enabled: true"), config);
        assertTrue(config.contains("address: 'cache.example:6380'"), config);
        assertTrue(config.contains("password: 'rot'"),
                "Passwort kommt aus der Redis-URI: " + config);
    }

    @Test
    void velocityGetsItsOwnJarAndLowercaseConfig() throws Exception {
        Path service = temp.resolve("static").resolve("Proxy-1");

        installer.install(group("Proxy", "velocity"), service, mongo(), redis());

        assertTrue(Files.isRegularFile(service.resolve("plugins/luckperms-velocity.jar")));
        assertTrue(Files.isRegularFile(service.resolve("plugins/luckperms/config.yml")));
    }

    @Test
    void proxyPlatformsShareTheBungeeArtifact() throws Exception {
        Path service = temp.resolve("static").resolve("Proxy-1");

        installer.install(group("Proxy", "waterfall"), service, mongo(), redis());

        assertTrue(Files.isRegularFile(service.resolve("plugins/luckperms-bungee.jar")));
        assertTrue(Files.isRegularFile(service.resolve("plugins/LuckPerms/config.yml")));
    }

    @Test
    void modLoaderPlatformsUseModsAndHocon() throws Exception {
        Path service = temp.resolve("running").resolve("Fabric-1");

        installer.install(group("Fabric", "fabric"), service, mongo(), redis());

        assertTrue(Files.isRegularFile(service.resolve("mods/luckperms-fabric.jar")),
                "Fabric lädt aus mods/, nicht aus plugins/");
        String config = Files.readString(service.resolve("config/luckperms/luckperms.conf"));
        assertTrue(config.contains("storage-method=\"mongodb\""), config);
        assertTrue(config.contains("data {"), config);
        assertTrue(config.contains("messaging-service=\"redis\""), config);
        assertTrue(config.contains("enabled=true"), config);
    }

    @Test
    void theJarIsFetchedOnceAndReused() throws Exception {
        Path first = temp.resolve("running").resolve("Lobby-1");
        Path second = temp.resolve("running").resolve("Lobby-2");

        installer.install(group("Lobby", "paper"), first, mongo(), redis());
        installer.install(group("Lobby", "paper"), second, mongo(), redis());

        assertEquals(1, metadataHits.get(), "die Download-Liste wird je Plattform einmal gelesen");
        assertEquals(1, downloads.get(), "die geprüfte Jar kommt aus dem Cache");
        assertTrue(Files.isRegularFile(second.resolve("plugins/luckperms-bukkit.jar")));
    }

    @Test
    void anExistingConfigurationStaysUntouched() throws Exception {
        Path service = temp.resolve("static").resolve("Proxy-1");
        Path config = service.resolve("plugins/luckperms/config.yml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "storage-method: yaml\n");

        installer.install(group("Proxy", "velocity"), service, mongo(), redis());

        assertEquals("storage-method: yaml\n", Files.readString(config),
                "Anpassungen des Betreibers bleiben erhalten");
    }

    @Test
    void unsupportedPlatformsAreSkipped() throws Exception {
        Path service = temp.resolve("running").resolve("Mystery-1");

        assertTrue(installer.install(group("Mystery", "hytale"), service, mongo(), redis()).isEmpty());
        assertFalse(Files.exists(service));
        assertEquals(0, metadataHits.get(), "ohne passendes Paket keine API-Abfrage");
    }

    @Test
    void aBrokenMetadataEndpointNamesThePlatform() throws Exception {
        server.stop(0);

        LuckPermsException error = assertThrows(LuckPermsException.class,
                () -> installer.install(group("Lobby", "paper"),
                        temp.resolve("running").resolve("Lobby-1"), mongo(), redis()));

        assertTrue(error.getMessage().contains("bukkit"), error.getMessage());
    }

    private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals(LuckPermsInstaller.DOWNLOADS_PATH)) {
            metadataHits.incrementAndGet();
            send(exchange, 200, "{\"downloads\":{"
                    + "\"bukkit\":\"" + base() + "/files/LuckPerms-Bukkit-5.5.88.jar\","
                    + "\"bungee\":\"" + base() + "/files/LuckPerms-Bungee-5.5.88.jar\","
                    + "\"velocity\":\"" + base() + "/files/LuckPerms-Velocity-5.5.88.jar\","
                    + "\"fabric\":\"" + base() + "/files/LuckPerms-Fabric-5.5.88.jar\""
                    + "}}");
            return;
        }
        byte[] body = switch (path) {
            case "/files/LuckPerms-Bukkit-5.5.88.jar" -> BUKKIT_JAR;
            case "/files/LuckPerms-Velocity-5.5.88.jar" -> VELOCITY_JAR;
            case "/files/LuckPerms-Bungee-5.5.88.jar" -> BUKKIT_JAR;
            case "/files/LuckPerms-Fabric-5.5.88.jar" -> FABRIC_JAR;
            default -> null;
        };
        if (body == null) {
            send(exchange, 404, "");
            return;
        }
        downloads.incrementAndGet();
        send(exchange, 200, body);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static dev.bytestorm.proto.ServiceGroup group(String name, String platform) {
        return dev.bytestorm.proto.ServiceGroup.newBuilder()
                .setName(name)
                .setPlatform(platform)
                .build();
    }

    private static MongoSettings mongo() {
        return new MongoSettings("mongodb://localhost:27017", null);
    }

    private static RedisSettings redis() {
        return new RedisSettings("redis://localhost:6379", null);
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        send(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
