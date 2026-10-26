package dev.bytestorm.node.lifecycle;

import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryPreparerTest {

    @TempDir
    Path temp;

    private final DirectoryPreparer preparer = new DirectoryPreparer();

    @Test
    void prepareCreatesTheServiceDirectory() throws Exception {
        ServiceDefinition definition = definition(ServiceMode.DYNAMIC);

        preparer.prepare(definition);

        assertTrue(Files.isDirectory(definition.directory()));
    }

    @Test
    void cleanupDeletesOnlyDynamicDirectories() throws Exception {
        ServiceDefinition dynamic = definition(ServiceMode.DYNAMIC);
        ServiceDefinition statik = definition(ServiceMode.STATIC);
        Files.createDirectories(dynamic.directory().resolve("world"));
        Files.createDirectories(statik.directory().resolve("world"));

        preparer.cleanup(dynamic);
        preparer.cleanup(statik);

        assertFalse(Files.exists(dynamic.directory()));
        assertTrue(Files.isDirectory(statik.directory()));
    }

    @Test
    void reapplyIsANoOp() throws Exception {
        ServiceDefinition definition = definition(ServiceMode.DYNAMIC);

        preparer.reapply(definition);

        assertFalse(Files.exists(definition.directory()));
    }

    private ServiceDefinition definition(ServiceMode mode) {
        String name = mode == ServiceMode.DYNAMIC ? "Lobby-1" : "Proxy";
        Path directory = temp.resolve(name);
        return new ServiceDefinition(ServiceId.random(), name,
                ServiceGroup.newBuilder().setName(name).setMode(mode).build(),
                directory, directory.resolve("server.jar"), null,
                LaunchOptions.builder().memoryMb(512).build(),
                new RedisSettings("redis://localhost:6379", "secret"));
    }
}
