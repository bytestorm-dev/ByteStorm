package dev.bytestorm.node.runtime;

import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.RedisSettings;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceLaunchTest {

    private final LaunchOptions options = LaunchOptions.builder().memoryMb(512).build();
    private final RedisSettings redis = new RedisSettings("redis://localhost:6379", "secret");

    @Test
    void normalizesPathsAndKeepsANullBridge() {
        ServiceLaunch launch = new ServiceLaunch(ServiceId.random(), "Lobby-1", PortLease.fixed(30001),
                Path.of("running/../running/Lobby-1"), Path.of("running/Lobby-1/server.jar"),
                null, options, redis);

        assertEquals(Path.of("running/Lobby-1").toAbsolutePath().normalize(), launch.directory());
        assertNull(launch.bridgeJar());
    }

    @Test
    void rejectsNamesThatWouldEscapeTheLogDirectory() {
        for (String name : new String[]{"", " ", "..", "a/b", "a\\b"}) {
            assertThrows(IllegalArgumentException.class, () -> launch(name), "Name: '" + name + "'");
        }
    }

    @Test
    void trimsTheName() {
        assertEquals("Lobby-1", launch("  Lobby-1  ").name());
    }

    @Test
    void requiresAPortLease() {
        assertThrows(NullPointerException.class, () -> new ServiceLaunch(ServiceId.random(), "Lobby-1", null,
                Path.of("service"), Path.of("service/server.jar"), null, options, redis));
    }

    private ServiceLaunch launch(String name) {
        return new ServiceLaunch(ServiceId.random(), name, PortLease.fixed(25565), Path.of("service"),
                Path.of("service/server.jar"), null, options, redis);
    }
}
