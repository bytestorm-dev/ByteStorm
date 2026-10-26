package dev.bytestorm.core.mongo;

import dev.bytestorm.core.config.MongoSettings;
import dev.bytestorm.core.mongo.model.ModuleConfig;
import dev.bytestorm.core.mongo.model.StoredPlayer;
import dev.bytestorm.core.mongo.repository.GroupRepository;
import dev.bytestorm.core.mongo.repository.ModuleConfigRepository;
import dev.bytestorm.core.mongo.repository.PlayerRepository;
import dev.bytestorm.core.mongo.repository.ScalingRuleRepository;
import dev.bytestorm.core.mongo.repository.TemplateRepository;
import dev.bytestorm.core.support.TestServers;
import dev.bytestorm.proto.PlayerCountRule;
import dev.bytestorm.proto.PlayerInfo;
import dev.bytestorm.proto.ScalingRule;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceType;
import dev.bytestorm.proto.TemplateInfo;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepositoryIntegrationTest {

    private MongoProvider provider;

    @BeforeAll
    void connect() {
        TestServers.assumeMongo();
        provider = MongoProvider.connect(new MongoSettings(TestServers.mongoUri(), TestServers.uniqueDatabase()));
    }

    @AfterAll
    void cleanup() {
        if (provider != null) {
            provider.database().drop();
            provider.close();
        }
    }

    private static ServiceGroup lobby() {
        return ServiceGroup.newBuilder()
                .setName("Lobby")
                .setType(ServiceType.SERVER)
                .setPlatform("paper")
                .setVersion("26.3")
                .setMode(ServiceMode.DYNAMIC)
                .addTemplates("global")
                .setMinServices(1)
                .setMaxServices(5)
                .setMaxPlayers(100)
                .setMemory(2048)
                .setFallback(true)
                .setFallbackPriority(10)
                .putProperties("motd", "Willkommen")
                .addScalingRules(ScalingRule.newBuilder()
                        .setId("players")
                        .setEnabled(true)
                        .setPlayerCount(PlayerCountRule.newBuilder()
                                .setScaleUpUtilization(0.8)
                                .setIdleMinutes(5)))
                .build();
    }

    @Test
    void playerNameIndexIsCreated() {
        // Das Repository erzeugt die Indizes aus den Entity-Annotations.
        new PlayerRepository(provider);

        List<Document> indexes = provider.database()
                .getCollection("players")
                .listIndexes()
                .into(new ArrayList<>());

        assertTrue(indexes.stream().anyMatch(index -> index.get("name").equals("name_1")),
                "Index auf players.name erwartet, gefunden: " + indexes);
    }

    @Test
    void documentsStayReadable() {
        new GroupRepository(provider).upsert(lobby());

        Document document = provider.database().getCollection("groups").find(eq("_id", "Lobby")).first();

        assertEquals("SERVER", document.getString("type"), "Enums stehen als Name im Dokument");
        assertEquals("paper", document.getString("platform"));
        assertEquals("Willkommen", document.get("properties", Document.class).getString("motd"));
        assertTrue(document.getList("templates", String.class).contains("global"));
        assertEquals("PLAYER_COUNT",
                document.getList("scalingRules", Document.class).get(0).getString("type"));
    }

    @Test
    void groupRepositoryCrud() {
        GroupRepository repository = new GroupRepository(provider);

        repository.upsert(lobby());

        assertEquals(lobby(), repository.find("Lobby").orElseThrow());
        assertEquals(1, repository.findAll().size());

        assertTrue(repository.setMaintenance("Lobby", true));
        assertTrue(repository.find("Lobby").orElseThrow().getMaintenance());
        assertFalse(repository.setMaintenance("Unknown", true));

        assertTrue(repository.delete("Lobby"));
        assertTrue(repository.find("Lobby").isEmpty());
    }

    @Test
    void templateRepositoryCrud() {
        TemplateRepository repository = new TemplateRepository(provider);
        TemplateInfo template = TemplateInfo.newBuilder()
                .setName("lobby")
                .setDescription("Lobby")
                .setHash("abc")
                .setLastModified(1_700_000_000_000L)
                .build();

        repository.upsert(template);

        assertEquals(template, repository.find("lobby").orElseThrow());
        assertTrue(repository.delete("lobby"));
        assertTrue(repository.find("lobby").isEmpty());
    }

    @Test
    void playerRepositoryTracksPresenceAndPlaytime() {
        PlayerRepository repository = new PlayerRepository(provider);
        UUID uuid = UUID.randomUUID();
        Instant first = Instant.parse("2026-01-01T10:00:00Z");

        repository.recordPresence(PlayerInfo.newBuilder()
                .setUuid(uuid.toString())
                .setName("Notch")
                .setServer("Lobby-1")
                .build(), first);
        repository.recordPresence(PlayerInfo.newBuilder()
                .setUuid(uuid.toString())
                .setName("Notch")
                .setServer("Lobby-2")
                .build(), first.plusSeconds(600));
        repository.addPlaytime(uuid, Duration.ofMinutes(30));

        StoredPlayer stored = repository.find(uuid).orElseThrow();
        assertEquals("Lobby-2", stored.lastService());
        assertEquals("Notch", repository.findByName("notch").orElseThrow().name());
        assertEquals(Duration.ofMinutes(30), stored.playtime());
        assertEquals(first, stored.firstSeen(), "firstSeen bleibt beim Update erhalten");
    }

    @Test
    void scalingRuleRepositoryReplacesRules() {
        ScalingRuleRepository repository = new ScalingRuleRepository(provider);
        ScalingRule rule = lobby().getScalingRules(0);

        repository.replace("Lobby", List.of(rule));

        List<ScalingRule> loaded = repository.findByGroup("Lobby");
        assertEquals(1, loaded.size());
        assertEquals(rule, loaded.get(0));

        repository.replace("Lobby", List.of());
        assertTrue(repository.findByGroup("Lobby").isEmpty());

        repository.replace("Lobby", List.of(rule));
        repository.deleteByGroup("Lobby");
        assertTrue(repository.findByGroup("Lobby").isEmpty());
    }

    @Test
    void moduleConfigRepositoryCrud() {
        ModuleConfigRepository repository = new ModuleConfigRepository(provider);
        ModuleConfig config = new ModuleConfig("rest", true, Map.of("port", "8080"));

        repository.upsert(config);

        assertEquals(config, repository.find("rest").orElseThrow());
        assertEquals(1, repository.findAll().size());
    }
}
