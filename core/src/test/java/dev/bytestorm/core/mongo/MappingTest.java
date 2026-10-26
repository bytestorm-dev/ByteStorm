package dev.bytestorm.core.mongo;

import dev.bytestorm.core.mongo.entity.GroupEntity;
import dev.bytestorm.core.mongo.entity.ModuleConfigEntity;
import dev.bytestorm.core.mongo.entity.PlayerEntity;
import dev.bytestorm.core.mongo.entity.ScalingRuleData;
import dev.bytestorm.core.mongo.entity.TemplateEntity;
import dev.bytestorm.core.mongo.mapping.ModuleConfigMapper;
import dev.bytestorm.core.mongo.mapping.PlayerMapper;
import dev.bytestorm.core.mongo.mapping.ScalingRuleMapper;
import dev.bytestorm.core.mongo.mapping.ServiceGroupMapper;
import dev.bytestorm.core.mongo.mapping.TemplateMapper;
import dev.bytestorm.core.mongo.model.ModuleConfig;
import dev.bytestorm.core.mongo.model.StoredPlayer;
import dev.bytestorm.proto.LoadRule;
import dev.bytestorm.proto.PlayerCountRule;
import dev.bytestorm.proto.PlayerInfo;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ScheduleRule;
import dev.bytestorm.proto.ScalingRule;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceType;
import dev.bytestorm.proto.TemplateInfo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingTest {

    private static ScalingRule playerRule() {
        return ScalingRule.newBuilder()
                .setId("players")
                .setEnabled(true)
                .setPlayerCount(PlayerCountRule.newBuilder()
                        .setScaleUpUtilization(0.8)
                        .setScaleUpServices(1)
                        .setIdleUtilization(0.1)
                        .setIdleMinutes(5))
                .build();
    }

    @Test
    void groupRoundtripKeepsAllFields() {
        ServiceGroup original = ServiceGroup.newBuilder()
                .setName("Lobby")
                .setType(ServiceType.SERVER)
                .setPlatform("paper")
                .setVersion("26.3")
                .setMode(ServiceMode.DYNAMIC)
                .addTemplates("global")
                .addTemplates("lobby")
                .setMinServices(2)
                .setMaxServices(10)
                .setMaxPlayers(100)
                .setMemory(2048)
                .addJvmArgs("-XX:+UseG1GC")
                .addJavaArgs("nogui")
                .setFallback(true)
                .setFallbackPriority(10)
                .setMaintenance(false)
                .putProperties("motd", "Willkommen")
                .setRuntime(RuntimeType.DOCKER)
                .setJavaVersion(26)
                .addScalingRules(playerRule())
                .build();

        GroupEntity entity = ServiceGroupMapper.toEntity(original);

        assertEquals(original, ServiceGroupMapper.toProto(entity));
        assertEquals("SERVER", entity.getType(), "Enums bleiben lesbar");
        assertEquals("Willkommen", entity.getProperties().get("motd"));
        assertEquals("PLAYER_COUNT", entity.getScalingRules().get(0).getType());
    }

    @Test
    void optionalFieldsAreOmitted() {
        ServiceGroup group = ServiceGroup.newBuilder().setName("Proxy").build();

        GroupEntity entity = ServiceGroupMapper.toEntity(group);

        assertNull(entity.getRuntime());
        assertNull(entity.getJavaVersion());
        assertFalse(ServiceGroupMapper.toProto(entity).hasRuntime());
    }

    @Test
    void scalingRuleTypesRoundtrip() {
        ScalingRule schedule = ScalingRule.newBuilder()
                .setId("evening")
                .setEnabled(true)
                .setSchedule(ScheduleRule.newBuilder()
                        .setCron("0 0 18 * * *")
                        .setTimezone("Europe/Berlin")
                        .setMinServices(3)
                        .setMaxServices(8))
                .build();
        ScalingRule load = ScalingRule.newBuilder()
                .setId("host")
                .setEnabled(false)
                .setLoad(LoadRule.newBuilder()
                        .setMaxHostCpu(0.9)
                        .setMaxHostMemory(0.85)
                        .setMinServices(1))
                .build();

        ScalingRuleData scheduleData = ScalingRuleMapper.toData(schedule);
        ScalingRuleData loadData = ScalingRuleMapper.toData(load);

        assertEquals("SCHEDULE", scheduleData.getType());
        assertEquals(schedule, ScalingRuleMapper.toProto(scheduleData));
        assertEquals(load, ScalingRuleMapper.toProto(loadData));
    }

    @Test
    void templateRoundtrip() {
        TemplateInfo original = TemplateInfo.newBuilder()
                .setName("lobby")
                .setDescription("Lobby-Server")
                .setHash("abc123")
                .setLastModified(1_700_000_000_000L)
                .build();

        TemplateEntity entity = TemplateMapper.toEntity(original);

        assertEquals(original, TemplateMapper.toProto(entity));
    }

    @Test
    void playerRoundtrip() {
        StoredPlayer original = new StoredPlayer(
                UUID.randomUUID(),
                "Notch",
                Duration.ofHours(3),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z"),
                "Lobby-1");

        PlayerEntity entity = PlayerMapper.toEntity(original);

        assertEquals(original, PlayerMapper.toDomain(entity));
        PlayerInfo proto = PlayerMapper.toDomain(entity).toProto();
        assertEquals("Notch", proto.getName());
        assertEquals("Lobby-1", proto.getServer());
    }

    @Test
    void moduleConfigRoundtrip() {
        ModuleConfig original = new ModuleConfig("rest", true, Map.of("port", "8080"));

        ModuleConfigEntity entity = ModuleConfigMapper.toEntity(original);

        assertEquals(original, ModuleConfigMapper.toDomain(entity));
        assertTrue(entity.isEnabled());
    }
}
