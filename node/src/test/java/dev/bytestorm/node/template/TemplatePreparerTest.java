package dev.bytestorm.node.template;

import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.node.lifecycle.ServiceDefinition;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplatePreparerTest {

    @TempDir
    Path temp;

    @Test
    void laterLayersOverrideEarlierOnes() throws Exception {
        write("ALL/a.txt", "all");
        write("SERVER/a.txt", "server");
        write("FALLBACK/a.txt", "fallback");
        TemplatePreparer preparer = new TemplatePreparer(temp.resolve("templates"));

        preparer.prepare(definition("Lobby-1", ServiceType.SERVER, true), 30001);
        preparer.prepare(definition("Lobby-2", ServiceType.SERVER, false), 30002);
        preparer.prepare(definition("Proxy-1", ServiceType.PROXY, false), 25565);

        assertEquals("fallback", read(serviceDir("Lobby-1").resolve("a.txt")));
        assertEquals("server", read(serviceDir("Lobby-2").resolve("a.txt")));
        assertEquals("all", read(serviceDir("Proxy-1").resolve("a.txt")));
    }

    @Test
    void groupTemplatesOverrideTheFixedLayers() throws Exception {
        write("ALL/a.txt", "all");
        write("SERVER/a.txt", "server");
        write("FALLBACK/a.txt", "fallback");
        write("Lobby/a.txt", "group");
        TemplatePreparer preparer = new TemplatePreparer(temp.resolve("templates"));
        ServiceGroup group = group(ServiceType.SERVER, true).toBuilder()
                .addTemplates("Lobby")
                .build();

        preparer.prepare(definition("Lobby-1", group), 30001);

        assertEquals("group", read(serviceDir("Lobby-1").resolve("a.txt")));
    }

    @Test
    void placeholdersAreReplacedInTextFiles() throws Exception {
        write("ALL/server.properties", """
                port=%PORT%
                name=%NAME%
                group=%GROUP%
                max=%MAXPLAYERS%
                memory=%MEMORY%
                secret=%SECRET%
                """);
        TemplatePreparer preparer = new TemplatePreparer(temp.resolve("templates"));

        preparer.prepare(definition("Lobby-1", ServiceType.SERVER, true), 30001);

        assertEquals("""
                port=30001
                name=Lobby-1
                group=Lobby
                max=100
                memory=512
                secret=%SECRET%
                """, read(serviceDir("Lobby-1").resolve("server.properties")));
    }

    @Test
    void forwardingSecretReplacesTheSecretPlaceholder() throws Exception {
        write("ALL/forwarding.secret", "%SECRET%");
        write("ALL/config/paper-global.yml", """
                proxies:
                  velocity:
                    enabled: true
                    secret: '%SECRET%'
                """);
        TemplatePreparer preparer = new TemplatePreparer(temp.resolve("templates"), "a1b2c3");

        preparer.prepare(definition("Proxy-1", ServiceType.PROXY, false), 25565);

        assertEquals("a1b2c3", read(serviceDir("Proxy-1").resolve("forwarding.secret")));
        assertTrue(read(serviceDir("Proxy-1").resolve("config/paper-global.yml"))
                .contains("secret: 'a1b2c3'"));
    }

    @Test
    void blankSecretLeavesThePlaceholder() throws Exception {
        write("ALL/forwarding.secret", "%SECRET%");
        TemplatePreparer preparer = new TemplatePreparer(temp.resolve("templates"), "  ");

        preparer.prepare(definition("Proxy-1", ServiceType.PROXY, false), 25565);

        assertEquals("%SECRET%", read(serviceDir("Proxy-1").resolve("forwarding.secret")));
    }

    @Test
    void binaryFilesStayUntouched() throws Exception {
        byte[] binary = {0x00, 0x01, (byte) 0xff, 0x7f, 0x00, 0x42};
        Path template = temp.resolve("templates/ALL/blob.bin");
        Files.createDirectories(template.getParent());
        Files.write(template, binary);
        TemplatePreparer preparer = new TemplatePreparer(temp.resolve("templates"));

        preparer.prepare(definition("Lobby-1", ServiceType.SERVER, true), 30001);

        assertArrayEquals(binary, Files.readAllBytes(serviceDir("Lobby-1").resolve("blob.bin")));
    }

    @Test
    void ignoreFileSkipsMatchingPaths() throws Exception {
        write("ALL/.bytestorm-ignore", "# Kommentar\n\nlogs/\n*.log\n");
        write("ALL/config.yml", "ok");
        write("ALL/logs/latest.txt", "ignored");
        write("ALL/debug.log", "ignored");
        TemplatePreparer preparer = new TemplatePreparer(temp.resolve("templates"));

        preparer.prepare(definition("Lobby-1", ServiceType.SERVER, true), 30001);

        Path directory = serviceDir("Lobby-1");
        assertTrue(Files.isRegularFile(directory.resolve("config.yml")));
        assertFalse(Files.exists(directory.resolve("logs/latest.txt")));
        assertFalse(Files.exists(directory.resolve("debug.log")));
        assertFalse(Files.exists(directory.resolve(".bytestorm-ignore")));
    }

    @Test
    void staticServiceGetsTemplatesOnlyOnFirstCreation() throws Exception {
        write("ALL/a.txt", "template");
        TemplatePreparer preparer = new TemplatePreparer(temp.resolve("templates"));
        ServiceDefinition definition = definition("Static-1", ServiceType.SERVER, false,
                ServiceMode.STATIC);
        Files.createDirectories(definition.directory());
        Files.writeString(definition.directory().resolve("marker.txt"), "keep");

        preparer.prepare(definition, 30001);

        assertFalse(Files.exists(definition.directory().resolve("a.txt")));
        assertEquals("keep", read(definition.directory().resolve("marker.txt")));

        ServiceDefinition fresh = definition("Static-2", ServiceType.SERVER, false,
                ServiceMode.STATIC);
        preparer.prepare(fresh, 30002);
        assertEquals("template", read(fresh.directory().resolve("a.txt")));
    }

    @Test
    void dynamicServiceAppliesTemplatesOnEveryStart() throws Exception {
        write("ALL/a.txt", "v1");
        TemplatePreparer preparer = new TemplatePreparer(temp.resolve("templates"));
        ServiceDefinition definition = definition("Lobby-1", ServiceType.SERVER, true);

        preparer.prepare(definition, 30001);
        assertEquals("v1", read(definition.directory().resolve("a.txt")));

        write("ALL/a.txt", "v2");
        preparer.prepare(definition, 30002);
        assertEquals("v2", read(definition.directory().resolve("a.txt")));
    }

    @Test
    void cleanupRemovesOnlyDynamicDirectories() throws Exception {
        write("ALL/a.txt", "x");
        TemplatePreparer preparer = new TemplatePreparer(temp.resolve("templates"));
        ServiceDefinition dynamic = definition("Lobby-1", ServiceType.SERVER, true);
        ServiceDefinition statik = definition("Static-1", ServiceType.SERVER, false,
                ServiceMode.STATIC);

        preparer.prepare(dynamic, 30001);
        preparer.prepare(statik, 30002);
        preparer.cleanup(dynamic);
        preparer.cleanup(statik);

        assertFalse(Files.exists(dynamic.directory()));
        assertTrue(Files.isRegularFile(statik.directory().resolve("a.txt")));
    }

    private void write(String relative, String content) throws IOException {
        Path file = temp.resolve("templates").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private Path serviceDir(String name) {
        return temp.resolve("running").resolve(name);
    }

    private ServiceDefinition definition(String name, ServiceType type, boolean fallback) {
        return definition(name, group(type, fallback), ServiceMode.DYNAMIC);
    }

    private ServiceDefinition definition(String name, ServiceType type, boolean fallback,
                                         ServiceMode mode) {
        return definition(name, group(type, fallback), mode);
    }

    private ServiceDefinition definition(String name, ServiceGroup group) {
        return definition(name, group, ServiceMode.DYNAMIC);
    }

    private ServiceDefinition definition(String name, ServiceGroup group, ServiceMode mode) {
        ServiceGroup withMode = group.toBuilder().setMode(mode).build();
        return new ServiceDefinition(ServiceId.random(), name, withMode,
                serviceDir(name), temp.resolve("platforms/server.jar"), null,
                LaunchOptions.builder().memoryMb(512).build(),
                new RedisSettings("redis://localhost:6379", null));
    }

    private static ServiceGroup group(ServiceType type, boolean fallback) {
        return ServiceGroup.newBuilder()
                .setName("Lobby")
                .setType(type)
                .setFallback(fallback)
                .setMode(ServiceMode.DYNAMIC)
                .setMaxPlayers(100)
                .build();
    }
}
