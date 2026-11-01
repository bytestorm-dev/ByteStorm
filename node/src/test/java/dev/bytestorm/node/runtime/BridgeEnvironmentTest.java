package dev.bytestorm.node.runtime;

import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.proto.RuntimeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeEnvironmentTest {

    @TempDir
    Path temp;

    @Test
    void handsOnlyTheFileNameToTheProcessAndKeepsTheSecretInTheFile() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("Lobby-1"));

        Map<String, String> environment = BridgeEnvironment.of(launch(directory, "geheim"));

        assertEquals(Map.of(BridgeEnvironment.BRIDGE_FILE, BridgeEnvironment.FILE_NAME), environment,
                "in der Umgebung steht nur der Dateiname, nie das Passwort oder die Adresse");
        Path file = directory.resolve(BridgeEnvironment.FILE_NAME);
        assertTrue(Files.isRegularFile(file));
        Properties properties = read(file);
        assertEquals("Lobby-1", properties.getProperty("service.name"));
        assertEquals("redis://localhost:6379", properties.getProperty("redis.address"));
        assertEquals("geheim", properties.getProperty("redis.password"));
        if (Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class)) {
            assertEquals(Set.of(OWNER_READ, OWNER_WRITE), Files.getPosixFilePermissions(file),
                    "das Secret gehört nur dem Dienstbenutzer");
        }
    }

    @Test
    void withoutAPasswordNoPasswordKeyIsWritten() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("Proxy-1"));

        BridgeEnvironment.of(launch(directory, null));

        assertFalse(read(directory.resolve(BridgeEnvironment.FILE_NAME)).containsKey("redis.password"));
    }

    @Test
    void theFileIsReplacedOnEveryStart() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("Lobby-1"));
        BridgeEnvironment.of(launch(directory, "erstes"));

        BridgeEnvironment.of(launch(directory, "zweites"));

        assertEquals("zweites", read(directory.resolve(BridgeEnvironment.FILE_NAME))
                .getProperty("redis.password"));
        assertFalse(Files.exists(directory.resolve(BridgeEnvironment.FILE_NAME + ".tmp")),
                "die temporäre Datei bleibt nach dem Schreiben nicht liegen");
    }

    private ServiceLaunch launch(Path directory, String password) {
        return new ServiceLaunch(ServiceId.random(), directory.getFileName().toString(),
                PortLease.fixed(30001), directory,
                temp.resolve("server.jar"), null,
                LaunchOptions.builder().memoryMb(512).runtime(RuntimeType.PROCESS).build(),
                new RedisSettings("redis://localhost:6379", password));
    }

    private static Properties read(Path file) throws Exception {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        return properties;
    }
}
