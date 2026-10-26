package dev.bytestorm.node.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeInstallerTest {

    @TempDir
    Path temp;

    @Test
    void copiesTheBridgeIntoPlugins() throws IOException {
        Path service = Files.createDirectories(temp.resolve("service"));
        Path bridge = Files.writeString(temp.resolve("ByteStormBridge.jar"), "bridge-v1");

        BridgeInstaller.install(service, bridge);

        Path installed = service.resolve("plugins/ByteStormBridge.jar");
        assertArrayEquals(Files.readAllBytes(bridge), Files.readAllBytes(installed));
        try (var files = Files.list(service.resolve("plugins"))) {
            assertEquals(1, files.count(), "keine temporären Dateien zurücklassen");
        }
    }

    @Test
    void replacesAnOutdatedBridge() throws IOException {
        Path service = Files.createDirectories(temp.resolve("service"));
        Path plugins = Files.createDirectories(service.resolve("plugins"));
        Path bridge = Files.writeString(temp.resolve("ByteStormBridge.jar"), "bridge-v2");
        Files.writeString(plugins.resolve("ByteStormBridge.jar"), "bridge-v1");

        BridgeInstaller.install(service, bridge);

        assertEquals("bridge-v2", Files.readString(plugins.resolve("ByteStormBridge.jar")));
    }

    @Test
    void leavesAnIdenticalBridgeUntouched() throws IOException {
        Path service = Files.createDirectories(temp.resolve("service"));
        Path plugins = Files.createDirectories(service.resolve("plugins"));
        Path bridge = Files.writeString(temp.resolve("ByteStormBridge.jar"), "same");
        Path installed = Files.writeString(plugins.resolve("ByteStormBridge.jar"), "same");
        Files.setLastModifiedTime(installed, java.nio.file.attribute.FileTime.fromMillis(1_000));

        BridgeInstaller.install(service, bridge);

        assertEquals(1_000, Files.getLastModifiedTime(installed).toMillis());
    }

    @Test
    void failsWhenTheBridgeJarIsMissing() throws IOException {
        Path service = Files.createDirectories(temp.resolve("service"));

        IOException error = assertThrows(IOException.class,
                () -> BridgeInstaller.install(service, temp.resolve("missing.jar")));

        assertTrue(error.getMessage().contains("Bridge-Jar fehlt"), error.getMessage());
        assertFalse(Files.exists(service.resolve("plugins")));
    }

    @Test
    void withoutABridgeNothingHappens() throws IOException {
        Path service = Files.createDirectories(temp.resolve("service"));

        BridgeInstaller.install(service, null);

        assertFalse(Files.exists(service.resolve("plugins")));
    }
}
