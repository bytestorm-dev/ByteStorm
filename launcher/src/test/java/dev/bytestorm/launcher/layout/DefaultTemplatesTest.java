package dev.bytestorm.launcher.layout;

import dev.bytestorm.api.bridge.ProxyDefaults;
import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.node.lifecycle.ServiceDefinition;
import dev.bytestorm.node.template.TemplatePreparer;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTemplatesTest {

    @TempDir
    Path temp;

    private CloudLayout layout;

    @BeforeEach
    void setUp() {
        layout = new CloudLayout(temp.resolve("cloud"));
    }

    @Test
    void writesTheDefaultsForProxyAndServer() throws Exception {
        layout.create();

        List<Path> created = DefaultTemplates.write(layout);

        assertEquals(DefaultTemplates.relativePaths().size(), created.size());
        for (String relative : DefaultTemplates.relativePaths()) {
            assertTrue(Files.isRegularFile(layout.templates().resolve(relative)), relative);
        }
        assertTrue(Files.isRegularFile(layout.templates().resolve("PROXY/velocity.toml")));
        assertTrue(Files.isRegularFile(layout.templates().resolve("PROXY/" + DefaultTemplates.SECRET_FILE)));
        assertTrue(Files.isRegularFile(layout.templates().resolve("SERVER/server.properties")));
        assertFalse(Files.exists(layout.templates().resolve(DefaultTemplates.EULA_FILE)),
                "die EULA-Zustimmung ist eine Erklärung des Betreibers und entsteht nie von selbst");
        assertTrue(Files.isRegularFile(layout.templates().resolve("SERVER/" + DefaultTemplates.PAPER_GLOBAL_CONFIG)));
    }

    @Test
    void acceptingTheEulaWritesItIntoTheServerTemplate() throws Exception {
        layout.create();

        Path written = DefaultTemplates.acceptEula(layout);

        assertEquals(layout.templates().resolve(DefaultTemplates.EULA_FILE), written);
        String content = Files.readString(written);
        assertTrue(content.contains("eula=true"), content);
        assertTrue(content.contains("https://aka.ms/MinecraftEULA"),
                "die Datei nennt, wozu sie da ist: " + content);
    }

    @Test
    void proxyTemplateEnablesModernForwardingOnTheReservedPort() throws Exception {
        layout.create();
        DefaultTemplates.write(layout);

        String velocity = read("PROXY/velocity.toml");

        assertTrue(velocity.contains("player-info-forwarding-mode = \"modern\""), velocity);
        assertTrue(velocity.contains("forwarding-secret-file = \"" + DefaultTemplates.SECRET_FILE + "\""),
                "velocity.toml und die Secret-Datei müssen denselben Namen nennen");
        assertTrue(velocity.contains("bind = \"0.0.0.0:%PORT%\""), velocity);
        assertTrue(velocity.contains("online-mode = true"), "der Proxy authentifiziert die Spieler");
    }

    @Test
    void proxyTemplateSatisfiesTheServerListRequirement() throws Exception {
        layout.create();
        DefaultTemplates.write(layout);

        String velocity = read("PROXY/velocity.toml");

        // Velocity startet nur mit einem Server-Eintrag und einer nicht leeren Reihenfolge;
        // die Bridge entfernt genau diesen Platzhalter nach dem Start (§14).
        assertTrue(velocity.contains(ProxyDefaults.PLACEHOLDER_SERVER + " = \"127.0.0.1:1\""), velocity);
        assertTrue(velocity.contains("try = [\"" + ProxyDefaults.PLACEHOLDER_SERVER + "\"]"), velocity);

        // Ohne Versionsangabe hält Velocity die Datei für veraltet, ergänzt beim Start
        // Beispiel-Server und Forced Hosts aus seiner eigenen Vorgabe und verweigert dann
        // den Start, weil diese auf keine echten Services zeigen.
        assertTrue(velocity.contains("config-version = \"2.9\""), velocity);
        assertTrue(velocity.contains("[forced-hosts]"),
                "der leere Abschnitt hält die Beispiel-Forced-Hosts von Velocity fern");
    }

    @Test
    void serverTemplateDisablesAuthenticationAndUsesTheReservedPort() throws Exception {
        layout.create();
        DefaultTemplates.write(layout);

        String properties = read("SERVER/server.properties");
        String paper = read("SERVER/" + DefaultTemplates.PAPER_GLOBAL_CONFIG);

        assertTrue(properties.contains("online-mode=false"), properties);
        assertTrue(properties.contains("server-port=%PORT%"), properties);
        assertTrue(properties.contains("white-list=false"),
                "die Backend-Whitelist ist standardmäßig aus: " + properties);
        assertTrue(properties.contains("enforce-whitelist=false"), properties);
        assertTrue(paper.contains("enabled: true"), paper);
        assertTrue(paper.contains("online-mode: true"), "muss zu online-mode in velocity.toml passen");
        assertTrue(paper.contains("secret: '%SECRET%'"), paper);
    }

    @Test
    void existingFilesStayUntouched() throws Exception {
        layout.create();
        Path velocity = layout.templates().resolve("PROXY/velocity.toml");
        Files.createDirectories(velocity.getParent());
        Files.writeString(velocity, "bind = \"0.0.0.0:12345\"\n");

        List<Path> created = DefaultTemplates.write(layout);

        assertFalse(created.contains(velocity));
        assertEquals("bind = \"0.0.0.0:12345\"\n", Files.readString(velocity));
        assertTrue(Files.isRegularFile(layout.templates().resolve("SERVER/server.properties")),
                "fehlende Dateien werden weiterhin ergänzt");
    }

    @Test
    void templatesProduceAReadyProxyAndLobby() throws Exception {
        layout.create();
        DefaultTemplates.write(layout);
        TemplatePreparer preparer = new TemplatePreparer(layout.templates(), "geheim123");

        ServiceDefinition proxy = definition("Proxy-1", ServiceType.PROXY);
        ServiceDefinition lobby = definition("Lobby-1", ServiceType.SERVER);
        preparer.prepare(proxy, 25565);
        preparer.prepare(lobby, 30001);

        String velocity = Files.readString(proxy.directory().resolve("velocity.toml"));
        assertTrue(velocity.contains("bind = \"0.0.0.0:25565\""), velocity);
        assertTrue(velocity.contains("motd = \"ByteStorm Proxy\""), velocity);
        assertEquals("geheim123", Files.readString(proxy.directory().resolve(DefaultTemplates.SECRET_FILE)).trim(),
                "die Secret-Datei enthält nur das Secret");

        String paper = Files.readString(lobby.directory().resolve(DefaultTemplates.PAPER_GLOBAL_CONFIG));
        assertTrue(paper.contains("secret: 'geheim123'"), paper);
        String properties = Files.readString(lobby.directory().resolve("server.properties"));
        assertTrue(properties.contains("server-port=30001"), properties);
        assertTrue(properties.contains("online-mode=false"), properties);
    }

    @Test
    void withoutASecretThePlaceholderStays() throws Exception {
        layout.create();
        DefaultTemplates.write(layout);
        TemplatePreparer preparer = new TemplatePreparer(layout.templates());

        ServiceDefinition proxy = definition("Proxy-1", ServiceType.PROXY);
        preparer.prepare(proxy, 25565);

        assertTrue(Files.readString(proxy.directory().resolve(DefaultTemplates.SECRET_FILE))
                .contains("%SECRET%"));
    }

    private String read(String relative) throws Exception {
        return Files.readString(layout.templates().resolve(relative), StandardCharsets.UTF_8);
    }

    private ServiceDefinition definition(String name, ServiceType type) {
        ServiceGroup group = ServiceGroup.newBuilder()
                .setName(type == ServiceType.PROXY ? "Proxy" : "Lobby")
                .setType(type)
                .setPlatform(type == ServiceType.PROXY ? "velocity" : "paper")
                .setMode(ServiceMode.DYNAMIC)
                .setMinServices(1)
                .setMaxServices(1)
                .setMaxPlayers(100)
                .setMemory(512)
                .build();
        return new ServiceDefinition(ServiceId.random(), name, group,
                layout.running().resolve(name),
                layout.platforms().resolve("server.jar"), null,
                LaunchOptions.builder().memoryMb(512).build(),
                new RedisSettings("redis://localhost:6379", null));
    }
}
