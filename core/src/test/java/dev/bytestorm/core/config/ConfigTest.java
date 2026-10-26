package dev.bytestorm.core.config;

import dev.bytestorm.proto.RuntimeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    private static final String FULL_CONFIG = """
            {
              "mongo": { "uri": "mongodb://user:pass@localhost:27017", "database": "bytestorm" },
              "redis": { "address": "redis://localhost:6379", "password": "geheim" },
              "runtime": "DOCKER",
              "docker": { "host": "unix:///var/run/docker.sock" },
              "ports": { "proxy": 25565, "serverStart": 30000 },
              "modules": { "rest": true, "discord": false },
              "scaling": { "intervalSeconds": 4 },
              "crash": { "maxRestarts": 2, "backoffSeconds": [5, 15] }
            }
            """;

    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void emptyObjectYieldsDefaults() throws Exception {
        // redis.password ist Pflicht (§20), alles andere fällt auf Defaults zurück.
        CloudConfig config = loader.parse("{ \"redis\": { \"password\": \"x\" } }");

        assertEquals(MongoSettings.DEFAULT_URI, config.mongo().uri());
        assertEquals(MongoSettings.DEFAULT_DATABASE, config.mongo().database());
        assertEquals(RuntimeType.PROCESS, config.runtime());
        assertEquals(PortSettings.DEFAULT_PROXY, config.ports().proxy());
        assertEquals(PortSettings.DEFAULT_SERVER_START, config.ports().serverStart());
        assertEquals(ScalingSettings.DEFAULT_INTERVAL_SECONDS, config.scaling().intervalSeconds());
        assertEquals(CrashSettings.DEFAULT_MAX_RESTARTS, config.crash().maxRestarts());
        assertEquals(CrashSettings.DEFAULT_BACKOFF_SECONDS, config.crash().backoffSeconds());
        assertFalse(config.modules().rest());
        assertFalse(config.modules().discord());
        assertFalse(config.proxy().hasSecret(), "ein Secret entsteht erst beim Start (§14)");
    }

    @Test
    void fullConfigIsParsed() throws Exception {
        CloudConfig config = loader.parse(FULL_CONFIG);

        assertEquals("bytestorm", config.mongo().database());
        assertEquals("geheim", config.redis().password());
        assertEquals(RuntimeType.DOCKER, config.runtime());
        assertEquals(25565, config.ports().proxy());
        assertTrue(config.modules().rest());
        assertFalse(config.modules().discord());
        assertEquals(4, config.scaling().intervalSeconds());
        assertEquals(2, config.crash().maxRestarts());
        assertEquals(15, config.crash().backoffFor(9), "letzter Backoff wird wiederholt");
    }

    @Test
    void unknownFieldsAreIgnored() throws Exception {
        CloudConfig config = loader.parse("""
                { "redis": { "password": "x" }, "futureOption": { "nested": 1 } }
                """);

        assertEquals(MongoSettings.DEFAULT_URI, config.mongo().uri());
    }

    @Test
    void missingRedisPasswordIsRejected() {
        ConfigException error = assertThrows(ConfigException.class, () -> loader.parse("{}"));

        assertTrue(error.getMessage().contains("redis.password"), error.getMessage());
    }

    @Test
    void invalidRuntimeIsRejected() {
        ConfigException error = assertThrows(ConfigException.class,
                () -> loader.parse("""
                        { "redis": { "password": "x" }, "runtime": "KUBERNETES" }
                        """));

        assertTrue(error.getMessage().contains("Ungültiges JSON"), error.getMessage());
    }

    @Test
    void outOfRangePortsAreRejected() {
        ConfigException error = assertThrows(ConfigException.class,
                () -> loader.parse("""
                        { "redis": { "password": "x" }, "ports": { "proxy": 70000, "serverStart": 30000 } }
                        """));

        assertTrue(error.getMessage().contains("ports.proxy"), error.getMessage());
    }

    @Test
    void negativeBackoffIsRejected() {
        ConfigException error = assertThrows(ConfigException.class,
                () -> loader.parse("""
                        { "redis": { "password": "x" },
                          "crash": { "maxRestarts": 3, "backoffSeconds": [5, -1] } }
                        """));

        assertTrue(error.getMessage().contains("crash.backoffSeconds"), error.getMessage());
    }

    @Test
    void secretsAreRedactedInToString() throws Exception {
        CloudConfig config = loader.parse(FULL_CONFIG);

        String mongo = config.mongo().toString();
        String redis = config.redis().toString();

        assertFalse(mongo.contains("pass"), mongo);
        assertTrue(mongo.contains("user:***@"), mongo);
        assertFalse(redis.contains("geheim"), redis);
        assertEquals("geheim", config.redis().password(), "Redaktion betrifft nur die Ausgabe");
    }

    @Test
    void missingFileIsReported() {
        Path missing = Path.of("does", "not", "exist", "cloud.json");

        ConfigException error = assertThrows(ConfigException.class, () -> loader.load(missing));

        assertTrue(error.getMessage().contains("fehlt"), error.getMessage());
    }

    @Test
    void reloadAppliesNonCriticalChanges(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("cloud.json");
        Files.writeString(file, FULL_CONFIG);
        ConfigService service = ConfigService.load(file);

        Files.writeString(file, FULL_CONFIG.replace("\"intervalSeconds\": 4", "\"intervalSeconds\": 7"));
        CloudConfig reloaded = service.reload();

        assertEquals(7, reloaded.scaling().intervalSeconds());
        assertEquals(7, service.current().scaling().intervalSeconds());
    }

    @Test
    void reloadRejectsConnectionChanges(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("cloud.json");
        Files.writeString(file, FULL_CONFIG);
        ConfigService service = ConfigService.load(file);

        Files.writeString(file, FULL_CONFIG.replace("\"password\": \"geheim\"", "\"password\": \"neu\""));
        ConfigException error = assertThrows(ConfigException.class, service::reload);

        assertTrue(error.getMessage().contains("redis"), error.getMessage());
        assertTrue(error.getMessage().contains("Neustart"), error.getMessage());
    }

    @Test
    void forwardingSecretIsGeneratedAndStoredOnce(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("cloud.json");
        Files.writeString(file, FULL_CONFIG);
        ConfigService service = ConfigService.load(file);

        CloudConfig first = service.ensureForwardingSecret();
        String secret = first.proxy().forwardingSecret();

        assertTrue(first.proxy().hasSecret());
        assertEquals(secret, ConfigService.load(file).current().proxy().forwardingSecret(),
                "das Secret liegt in cloud.json");
        assertEquals(secret, service.ensureForwardingSecret().proxy().forwardingSecret(),
                "ein zweiter Aufruf würfelt das Secret nicht neu");
        assertEquals(secret, service.current().proxy().forwardingSecret());
    }

    @Test
    void configuredForwardingSecretIsKept(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("cloud.json");
        Files.writeString(file, FULL_CONFIG.replace("\"ports\"",
                "\"proxy\": { \"forwardingSecret\": \"vorhanden\" }, \"ports\""));
        ConfigService service = ConfigService.load(file);

        CloudConfig config = service.ensureForwardingSecret();

        assertEquals("vorhanden", config.proxy().forwardingSecret());
    }

    @Test
    void secretIsRedactedInToString() {
        ProxySettings settings = new ProxySettings("streng-geheim");

        assertFalse(settings.toString().contains("streng-geheim"), settings.toString());
        assertTrue(settings.toString().contains("***"));
        assertEquals("streng-geheim", new CloudConfig(null, null, null, null, null, settings, null, null, null)
                .proxy().forwardingSecret(), "Redaktion betrifft nur die Ausgabe");
    }

    @Test
    void reloadRejectsForwardingSecretChanges(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("cloud.json");
        Files.writeString(file, FULL_CONFIG.replace("\"ports\"",
                "\"proxy\": { \"forwardingSecret\": \"alt\" }, \"ports\""));
        ConfigService service = ConfigService.load(file);

        Files.writeString(file, FULL_CONFIG.replace("\"ports\"",
                "\"proxy\": { \"forwardingSecret\": \"neu\" }, \"ports\""));
        ConfigException error = assertThrows(ConfigException.class, service::reload);

        assertTrue(error.getMessage().contains("proxy.forwardingSecret"), error.getMessage());
        assertTrue(error.getMessage().contains("Neustart"), error.getMessage());
    }

    @Test
    void nullPasswordStaysNull() {
        RedisSettings settings = new RedisSettings("redis://localhost:6379", "  ");

        assertNull(settings.password());
    }
}
