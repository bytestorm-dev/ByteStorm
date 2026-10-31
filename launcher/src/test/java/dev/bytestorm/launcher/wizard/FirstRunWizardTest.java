package dev.bytestorm.launcher.wizard;

import dev.bytestorm.core.config.CloudConfig;
import dev.bytestorm.core.config.ConfigLoader;
import dev.bytestorm.core.config.MongoSettings;
import dev.bytestorm.core.config.PortSettings;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.core.mongo.model.ModuleConfig;
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
import java.util.Map;
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
    private final List<ModuleConfig> moduleCalls = new ArrayList<>();
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
        assertTrue(moduleCalls.isEmpty(), "ohne aktiviertes Modul entsteht keine Modul-Konfiguration");
    }

    @Test
    void anEnabledRestModuleIsConfiguredWithKeyHostAndPort() throws Exception {
        ScriptedWizardIo io = minimalAnswers()
                .answer("REST-Modul", "j")
                .answer("REST-Host", "")
                .answer("REST-Port", "8099")
                .answer("REST-API-Key", "geheim-geheim-1234")
                .answer("Discord-Modul", "n");

        CloudConfig config = wizard(io).run(temp);

        assertTrue(config.modules().rest());
        assertEquals(1, moduleCalls.size());
        ModuleConfig rest = moduleCalls.get(0);
        assertEquals("rest", rest.name());
        assertTrue(rest.enabled(), "das Modul wird als aktiviert hinterlegt");
        assertEquals(Map.of("host", "0.0.0.0", "port", "8099", "apiKey", "geheim-geheim-1234"),
                Map.copyOf(rest.settings()));
        // Der Key geht nur nach MongoDB — nie in eine Ausgabe (§20).
        assertTrue(io.messages().stream().noneMatch(message -> message.contains("geheim-geheim-1234")),
                io.messages().toString());
        assertTrue(io.messages().stream().anyMatch(message -> message.contains("Modul-Konfiguration")),
                io.messages().toString());
        // Die Modul-Fragen kommen direkt nach der Aktivierung, vor allen weiteren.
        List<String> asked = io.asked();
        int restQuestion = asked.indexOf("REST-Modul aktivieren?");
        List<String> restQuestions = asked.subList(restQuestion, restQuestion + 4);
        assertEquals("REST-Modul aktivieren?", restQuestions.get(0));
        assertEquals("REST-Host", restQuestions.get(1));
        assertEquals("REST-Port", restQuestions.get(2));
        assertEquals("REST-API-Key", restQuestions.get(3));
    }

    @Test
    void aShortRestKeyIsAskedAgainAndTheLongOneIsUsed() throws Exception {
        ScriptedWizardIo io = minimalAnswers()
                .answer("REST-Modul", "j")
                .answer("REST-Host", "127.0.0.1")
                .answer("REST-Port", "")
                .answer("REST-API-Key", "kurz", "geheim-geheim-1234")
                .answer("Discord-Modul", "n");

        wizard(io).run(temp);

        assertTrue(io.messages().stream().anyMatch(message -> message.contains("mindestens 8 Zeichen")),
                io.messages().toString());
        assertEquals(Map.of("host", "127.0.0.1", "port", "8080", "apiKey", "geheim-geheim-1234"),
                Map.copyOf(moduleCalls.get(0).settings()));
    }

    @Test
    void anEnabledDiscordModuleIsStoredWithoutSettings() throws Exception {
        ScriptedWizardIo io = minimalAnswers().answer("Discord-Modul", "j");

        wizard(io).run(temp);

        assertEquals(1, moduleCalls.size());
        assertEquals("discord", moduleCalls.get(0).name());
        assertTrue(moduleCalls.get(0).enabled());
        assertEquals(Map.of(), Map.copyOf(moduleCalls.get(0).settings()));
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
                .answer("MongoDB-URI", "mongodb://falsch:27017", "mongodb://richtig:27017");

        CloudConfig config = wizard(io).run(temp);

        assertEquals(2, mongoChecks.size());
        assertEquals("mongodb://richtig:27017", config.mongo().uri());
        assertTrue(io.notices().stream().anyMatch(message -> message.contains("MongoDB nicht erreichbar")),
                io.notices().toString());
        assertEquals(2, io.asked().stream().filter("MongoDB-URI"::equals).count(),
                "die betroffene Frage wird erneut gestellt");
    }

    @Test
    void embedsMongoCredentialsAndAsksAgainWithoutAPassword() throws Exception {
        ScriptedWizardIo io = minimalAnswers()
                .answer("MongoDB-Benutzer", "admin")
                .answer("MongoDB-Passwort", "", "p@ss:wort");

        CloudConfig config = wizard(io).run(temp);

        assertEquals(1, mongoChecks.size());
        assertEquals("mongodb://admin:p%40ss%3Awort@db:27017", mongoChecks.get(0).uri());
        assertEquals(mongoChecks.get(0), config.mongo());
        assertTrue(io.messages().stream().anyMatch(message -> message.contains("Passwort ist Pflicht")),
                io.messages().toString());
    }

    @Test
    void retriesRedisAfterAWrongPassword() throws Exception {
        redisFailures.set(1);
        ScriptedWizardIo io = minimalAnswers()
                .answer("Redis-Adresse", "redis://cache:6379", "redis://cache:6379")
                .answer("Redis-Passwort", "falsch", "geheim");

        CloudConfig config = wizard(io).run(temp);

        assertEquals(2, redisChecks.size());
        assertEquals("geheim", config.redis().password());
        assertTrue(io.notices().stream().anyMatch(message -> message.contains("Redis nicht erreichbar")),
                io.notices().toString());
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
    void anAbortedSecondRunWritesNothing() {
        mongoFailures.set(1);
        ScriptedWizardIo io = minimalAnswers()
                .answer("MongoDB-URI", "mongodb://falsch:27017")
                .abortOnNotice();

        assertThrows(WizardAbortedException.class, () -> wizard(io).run(temp));

        assertFalse(Files.exists(temp.resolve(FirstRunWizard.CONFIG_FILE)));
        assertTrue(io.notices().stream().anyMatch(message -> message.contains("MongoDB nicht erreichbar")));
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
    void processRuntimeKeepsTheDefaultDockerHost() throws Exception {
        CloudConfig config = wizard(minimalAnswers()).run(temp);

        assertEquals(dev.bytestorm.core.config.DockerSettings.DEFAULT_HOST, config.docker().host());
    }

    @Test
    void keepsTheConfigWhenDefaultGroupsFail() throws Exception {
        groupCreationFails = true;

        ScriptedWizardIo io = minimalAnswers();
        wizard(io).run(temp);

        assertTrue(Files.exists(temp.resolve(FirstRunWizard.CONFIG_FILE)));
        assertTrue(io.messages().stream().anyMatch(message -> message.contains("Standardgruppen konnten nicht")));
    }

    @Test
    void theFormOnlyShowsQuestionsThatApply() {
        Questionnaire form = FirstRunWizard.form();
        Answers answers = Answers.empty().with(FirstRunWizard.MONGO_USER, "");

        assertFalse(form.question(FirstRunWizard.MONGO_PASSWORD).visible(answers),
                "ohne Benutzer braucht es kein Passwort");
        assertTrue(form.question(FirstRunWizard.MONGO_PASSWORD).visible(
                answers.with(FirstRunWizard.MONGO_USER, "admin")));
        assertFalse(form.question(FirstRunWizard.DOCKER_HOST).visible(answers));
        assertTrue(form.question(FirstRunWizard.DOCKER_HOST)
                .visible(answers.with(FirstRunWizard.RUNTIME, "DOCKER")));
        assertFalse(form.question(FirstRunWizard.REST_HOST).visible(answers));
        assertTrue(form.question(FirstRunWizard.REST_HOST)
                .visible(answers.with(FirstRunWizard.REST, Answers.YES)));
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
        }, (settings, modules) -> moduleCalls.addAll(modules));
    }
}
