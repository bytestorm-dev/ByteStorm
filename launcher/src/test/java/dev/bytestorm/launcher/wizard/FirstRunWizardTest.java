package dev.bytestorm.launcher.wizard;

import dev.bytestorm.core.config.CloudConfig;
import dev.bytestorm.core.config.ConfigLoader;
import dev.bytestorm.core.config.MongoSettings;
import dev.bytestorm.core.config.PortSettings;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.proto.RuntimeType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirstRunWizardTest {

    @TempDir
    Path temp;

    private final List<MongoSettings> mongoChecks = new ArrayList<>();
    private final List<RedisSettings> redisChecks = new ArrayList<>();
    private final List<MongoSettings> groupCalls = new ArrayList<>();
    private final AtomicInteger mongoFailures = new AtomicInteger();
    private final AtomicInteger redisFailures = new AtomicInteger();
    private boolean groupCreationFails;

    @Test
    void writesConfigAndCreatesDefaultGroups() throws Exception {
        ScriptedWizardIo io = minimalAnswers();

        CloudConfig written = wizard(io).run(temp);

        Path file = temp.resolve(FirstRunWizard.CONFIG_FILE);
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(file.resolveSibling("cloud.json.tmp")));

        CloudConfig loaded = new ConfigLoader().load(file);
        assertEquals(written, loaded);
        assertEquals("mongodb://db:27017", loaded.mongo().uri());
        assertEquals("cloud", loaded.mongo().database());
        assertEquals("geheim", loaded.redis().password());
        assertEquals(RuntimeType.PROCESS, loaded.runtime());
        assertEquals(PortSettings.DEFAULT_PROXY, loaded.ports().proxy());
        assertEquals(PortSettings.DEFAULT_SERVER_START, loaded.ports().serverStart());
        assertFalse(loaded.modules().rest());
        assertFalse(loaded.modules().discord());

        assertEquals(1, groupCalls.size());
        assertEquals(loaded.mongo(), groupCalls.get(0));
        assertTrue(io.messages().stream().anyMatch(message -> message.contains("Konfiguration gespeichert")));
        assertTrue(io.messages().stream().anyMatch(message -> message.contains("Standardgruppen")));
    }

    @Test
    void writesTheConfigWithOwnerOnlyPermissions() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(temp).supportsFileAttributeView(PosixFileAttributeView.class),
                "Dateisystem ohne POSIX-Rechte");

        wizard(minimalAnswers()).run(temp);

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(temp.resolve(FirstRunWizard.CONFIG_FILE));
        assertEquals(Set.of(OWNER_READ, OWNER_WRITE), permissions);
    }

    @Test
    void retriesMongoAfterAFailedConnection() throws Exception {
        mongoFailures.set(1);
        ScriptedWizardIo io = minimalAnswers()
                .answer("MongoDB-URI", "mongodb://falsch:27017", "mongodb://richtig:27017")
                .answer("MongoDB-Benutzer", "", "")
                .answer("MongoDB-Datenbank", "cloud", "cloud")
                .answer("MongoDB-Zugang erneut eingeben?", "j");

        CloudConfig config = wizard(io).run(temp);

        assertEquals(2, mongoChecks.size());
        assertEquals("mongodb://richtig:27017", config.mongo().uri());
        assertTrue(io.messages().stream().anyMatch(message -> message.contains("MongoDB nicht erreichbar")));
    }

    @Test
    void embedsMongoCredentialsAndAsksAgainWithoutAPassword() throws Exception {
        ScriptedWizardIo io = minimalAnswers()
                .answer("MongoDB-URI", "mongodb://db:27017", "mongodb://db:27017")
                .answer("MongoDB-Benutzer", "admin", "admin")
                .answer("MongoDB-Passwort", "", "p@ss:wort")
                .answer("MongoDB-Datenbank", "cloud");

        CloudConfig config = wizard(io).run(temp);

        assertEquals(1, mongoChecks.size());
        assertEquals("mongodb://admin:p%40ss%3Awort@db:27017", mongoChecks.get(0).uri());
        assertEquals(mongoChecks.get(0), config.mongo());
        assertTrue(io.messages().stream().anyMatch(message -> message.contains("Passwort ist Pflicht")));
    }

    @Test
    void retriesRedisAfterAWrongPassword() throws Exception {
        redisFailures.set(1);
        ScriptedWizardIo io = minimalAnswers()
                .answer("Redis-Adresse", "redis://cache:6379", "redis://cache:6379")
                .answer("Redis-Passwort", "falsch", "geheim")
                .answer("Redis-Zugang erneut eingeben?", "j");

        CloudConfig config = wizard(io).run(temp);

        assertEquals(2, redisChecks.size());
        assertEquals("geheim", config.redis().password());
    }

    @Test
    void asksAgainForABlankPassword() throws Exception {
        ScriptedWizardIo io = minimalAnswers()
                .answer("Redis-Adresse", "redis://cache:6379", "redis://cache:6379")
                .answer("Redis-Passwort", "", "geheim");

        CloudConfig config = wizard(io).run(temp);

        assertEquals(1, redisChecks.size());
        assertEquals("geheim", config.redis().password());
        assertTrue(io.messages().stream().anyMatch(message -> message.contains("Pflicht")));
    }

    @Test
    void abortLeavesNoConfigAndNoTempFile() {
        ScriptedWizardIo io = minimalAnswers().failOn("Redis-Passwort");

        assertThrows(WizardAbortedException.class, () -> wizard(io).run(temp));

        assertFalse(Files.exists(temp.resolve(FirstRunWizard.CONFIG_FILE)));
        assertFalse(Files.exists(temp.resolve("config/cloud.json.tmp")));
        assertTrue(groupCalls.isEmpty());
    }

    @Test
    void declinedRetryAbortsWithoutWriting() {
        mongoFailures.set(1);
        ScriptedWizardIo io = minimalAnswers()
                .answer("MongoDB-URI", "mongodb://falsch:27017")
                .answer("MongoDB-Zugang erneut eingeben?", "n");

        assertThrows(WizardAbortedException.class, () -> wizard(io).run(temp));

        assertFalse(Files.exists(temp.resolve(FirstRunWizard.CONFIG_FILE)));
    }

    @Test
    void dockerRuntimeAsksForTheHost() throws Exception {
        ScriptedWizardIo io = minimalAnswers()
                .answer("Runtime", "DOCKER")
                .answer("Docker-Host", "unix:///tmp/docker.sock");

        CloudConfig config = wizard(io).run(temp);

        assertEquals(RuntimeType.DOCKER, config.runtime());
        assertEquals("unix:///tmp/docker.sock", config.docker().host());
    }

    @Test
    void keepsTheConfigWhenDefaultGroupsFail() throws Exception {
        groupCreationFails = true;

        ScriptedWizardIo io = minimalAnswers();
        wizard(io).run(temp);

        assertTrue(Files.exists(temp.resolve(FirstRunWizard.CONFIG_FILE)));
        assertTrue(io.messages().stream().anyMatch(message -> message.contains("Standardgruppen konnten nicht")));
    }

    private ScriptedWizardIo minimalAnswers() {
        return new ScriptedWizardIo()
                .answer("MongoDB-URI", "mongodb://db:27017")
                .answer("MongoDB-Benutzer", "")
                .answer("MongoDB-Datenbank", "cloud")
                .answer("Redis-Adresse", "redis://cache:6379")
                .answer("Redis-Passwort", "geheim")
                .answer("Runtime", "PROCESS")
                .answer("Proxy-Port", "")
                .answer("Server-Startport", "")
                .answer("REST-Modul", "n")
                .answer("Discord-Modul", "n");
    }

    private FirstRunWizard wizard(ScriptedWizardIo io) {
        ConnectionTester tester = new ConnectionTester() {
            @Override
            public void testMongo(MongoSettings settings) {
                mongoChecks.add(settings);
                if (mongoFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                    throw new IllegalStateException("MongoDB nicht erreichbar");
                }
            }

            @Override
            public void testRedis(RedisSettings settings) {
                redisChecks.add(settings);
                if (redisFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                    throw new IllegalStateException("Passwort falsch");
                }
            }
        };
        return new FirstRunWizard(io, tester, settings -> {
            groupCalls.add(settings);
            if (groupCreationFails) {
                throw new IllegalStateException("keine Rechte");
            }
        });
    }
}
