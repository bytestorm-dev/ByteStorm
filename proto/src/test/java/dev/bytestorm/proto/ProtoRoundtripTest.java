package dev.bytestorm.proto;

import com.google.protobuf.Any;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stellt sicher, dass alle Schemas verlustfrei serialisiert werden können —
 * Grundlage für den {@code ProtobufCodec} (Konzept §6.2).
 */
class ProtoRoundtripTest {

    private static ServiceInfo sampleService() {
        return ServiceInfo.newBuilder()
                .setId("6f1d1b4e-4f0e-4a9e-9f1a-2c3d4e5f6a7b")
                .setName("Lobby-1")
                .setGroup("Lobby")
                .setHost("127.0.0.1")
                .setPort(30000)
                .setState(ServiceState.ONLINE)
                .setOnlinePlayers(7)
                .setMaxPlayers(100)
                .putProperties("template", "lobby")
                .setStartedAt(1_700_000_000_000L)
                .setRuntime(RuntimeType.PROCESS)
                .build();
    }

    @Test
    void serviceInfoRoundtrip() throws Exception {
        ServiceInfo original = sampleService();

        ServiceInfo parsed = ServiceInfo.parseFrom(original.toByteArray());

        assertEquals(original, parsed);
        assertEquals(ServiceState.ONLINE, parsed.getState());
        assertEquals("lobby", parsed.getPropertiesOrThrow("template"));
    }

    @Test
    void serviceStateChangedRoundtrip() throws Exception {
        ServiceStateChanged original = ServiceStateChanged.newBuilder()
                .setService(sampleService())
                .setPrevious(ServiceState.STARTING)
                .build();

        ServiceStateChanged parsed = ServiceStateChanged.parseFrom(original.toByteArray());

        assertEquals(original, parsed);
        assertEquals(ServiceState.STARTING, parsed.getPrevious());
    }

    @Test
    void serviceGroupWithScalingRulesRoundtrip() throws Exception {
        ServiceGroup original = ServiceGroup.newBuilder()
                .setName("Lobby")
                .setType(ServiceType.SERVER)
                .setPlatform("paper")
                .setVersion("26.3")
                .setMode(ServiceMode.DYNAMIC)
                .addTemplates("global")
                .addTemplates("global_server")
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
                .addScalingRules(ScalingRule.newBuilder()
                        .setId("players")
                        .setEnabled(true)
                        .setPlayerCount(PlayerCountRule.newBuilder()
                                .setScaleUpUtilization(0.8)
                                .setScaleUpServices(1)
                                .setIdleUtilization(0.1)
                                .setIdleMinutes(5)))
                .addScalingRules(ScalingRule.newBuilder()
                        .setId("evening")
                        .setEnabled(true)
                        .setSchedule(ScheduleRule.newBuilder()
                                .setCron("0 0 18 * * *")
                                .setTimezone("Europe/Berlin")
                                .setMinServices(3)
                                .setMaxServices(10)))
                .build();

        ServiceGroup parsed = ServiceGroup.parseFrom(original.toByteArray());

        assertEquals(original, parsed);
        assertTrue(parsed.hasRuntime());
        assertEquals(RuntimeType.DOCKER, parsed.getRuntime());
        assertTrue(parsed.getScalingRules(0).hasPlayerCount());
        assertTrue(parsed.getScalingRules(1).hasSchedule());
    }

    @Test
    void bridgeHandshakeRoundtrip() throws Exception {
        BridgeHello hello = BridgeHello.newBuilder()
                .setProtocolMajor(ProtoVersion.MAJOR)
                .setProtocolMinor(ProtoVersion.MINOR)
                .setServiceId("6f1d1b4e-4f0e-4a9e-9f1a-2c3d4e5f6a7b")
                .setPlatform("paper")
                .setPlatformVersion("26.3")
                .build();

        BridgeHello parsed = BridgeHello.parseFrom(hello.toByteArray());

        assertEquals(hello, parsed);
        assertTrue(ProtoVersion.isCompatible(parsed.getProtocolMajor()));
    }

    @Test
    void anyRoundtrip() throws Exception {
        ServiceInfo original = sampleService();

        Any packed = Any.pack(original);
        assertTrue(packed.is(ServiceInfo.class));
        assertEquals(original, packed.unpack(ServiceInfo.class));
    }

    @Test
    void logLineRoundtrip() throws Exception {
        LogLine original = LogLine.newBuilder()
                .setService("Lobby-1")
                .setTime(1_700_000_000_000L)
                .setLine("[Server thread/INFO]: Done (2.341s)!")
                .build();

        assertEquals(original, LogLine.parseFrom(original.toByteArray()));
    }
}
