package dev.bytestorm.launcher.scaling;

import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.CloudConfig;
import dev.bytestorm.launcher.layout.CloudLayout;
import dev.bytestorm.node.lifecycle.ServiceDefinition;
import dev.bytestorm.node.lifecycle.ServiceLifecycleException;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceProvisionerTest {

    @TempDir
    Path temp;

    @Test
    void buildsADynamicDefinitionFromTheGroup() throws Exception {
        CloudLayout layout = layout();
        Files.createFile(layout.platforms().resolve("paper-1.20.jar"));
        Files.createFile(layout.lib().resolve("bridge-paper.jar"));
        ServiceGroup group = ServiceGroup.newBuilder()
                .setName("Lobby")
                .setPlatform("paper")
                .setVersion("1.20")
                .setMode(ServiceMode.DYNAMIC)
                .setMemory(2048)
                .addJvmArgs("-XX:+UseG1GC")
                .addJavaArgs("nogui")
                .setRuntime(RuntimeType.DOCKER)
                .setJavaVersion(21)
                .build();
        AtomicReference<ServiceDefinition> started = new AtomicReference<>();
        ServiceProvisioner provisioner = provisioner(layout, group, name -> false,
                (name, used) -> 3, started::set);

        ServiceInfo info = provisioner.start("Lobby");

        assertEquals("Lobby-3", info.getName());
        ServiceDefinition definition = started.get();
        assertEquals("Lobby-3", definition.name());
        assertEquals(group, definition.group());
        assertEquals(layout.platforms().resolve("paper-1.20.jar"), definition.platformJar());
        assertEquals(layout.lib().resolve("bridge-paper.jar"), definition.bridgeJar());
        assertEquals(layout.running().resolve("Lobby-3-" + definition.id()), definition.directory());
        assertEquals(2048, definition.options().memoryMb());
        assertEquals(List.of("-XX:+UseG1GC"), definition.options().jvmArgs());
        assertEquals(List.of("nogui"), definition.options().javaArgs());
        assertEquals(RuntimeType.DOCKER, definition.options().runtime());
        assertEquals(21, definition.options().javaVersion());
    }

    @Test
    void propertiesOfAStartOverrideThoseOfTheGroup() throws Exception {
        CloudLayout layout = layout();
        Files.createFile(layout.platforms().resolve("paper.jar"));
        ServiceGroup group = ServiceGroup.newBuilder()
                .setName("Lobby")
                .setPlatform("paper")
                .putProperties("template", "lobby")
                .putProperties("motd", "Willkommen")
                .build();
        AtomicReference<ServiceDefinition> started = new AtomicReference<>();
        ServiceProvisioner provisioner = provisioner(layout, group, name -> false,
                (name, used) -> 1, started::set);

        provisioner.start("Lobby", Map.of("template", "event", "event", "sommer"));

        assertEquals(Map.of("template", "event", "motd", "Willkommen", "event", "sommer"),
                started.get().group().getPropertiesMap());
        assertEquals("lobby", group.getPropertiesOrThrow("template"),
                "die gecachte Gruppe bleibt unverändert");

        provisioner.start("Lobby");
        assertEquals(group, started.get().group(), "ohne Properties gilt allein die Gruppe");

        assertThrows(IllegalArgumentException.class,
                () -> provisioner.start("Lobby", Map.of(" ", "leer")));
        assertThrows(IllegalArgumentException.class,
                () -> provisioner.start("Lobby", java.util.Collections.singletonMap("leer", null)));
    }

    @Test
    void staticGroupsUseTheirPersistentDirectory() throws Exception {
        CloudLayout layout = layout();
        Files.createFile(layout.platforms().resolve("paper.jar"));
        ServiceGroup group = ServiceGroup.newBuilder()
                .setName("Proxy")
                .setPlatform("paper")
                .setMode(ServiceMode.STATIC)
                .build();
        AtomicReference<ServiceDefinition> started = new AtomicReference<>();
        ServiceProvisioner provisioner = provisioner(layout, group, name -> false,
                (name, used) -> 1, started::set);

        provisioner.start("Proxy");

        assertEquals(layout.staticServices().resolve("Proxy-1"), started.get().directory());
        assertNull(started.get().bridgeJar());
    }

    @Test
    void platformProviderIsUsedWhenNoLocalJarExists() throws Exception {
        CloudLayout layout = layout();
        ServiceGroup group = ServiceGroup.newBuilder()
                .setName("Lobby")
                .setPlatform("paper")
                .setVersion("1.20.1")
                .build();
        Path downloaded = layout.platforms().resolve("paper-1.20.1.jar");
        AtomicReference<ServiceDefinition> started = new AtomicReference<>();
        ServiceProvisioner provisioner = new ServiceProvisioner(
                name -> Optional.of(group),
                (name, used) -> 1,
                name -> false,
                definition -> {
                    started.set(definition);
                    return info(definition);
                },
                (id, force) -> {
                    throw new AssertionError("stop nicht erwartet");
                },
                layout, CloudConfig.defaults(),
                (platform, version) -> {
                    assertEquals("paper", platform);
                    assertEquals("1.20.1", version);
                    try {
                        Files.createFile(downloaded);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return downloaded;
                });

        provisioner.start("Lobby");

        assertEquals(downloaded, started.get().platformJar());
    }

    @Test
    void missingPlatformJarExplainsWhereItBelongsTo() throws Exception {
        CloudLayout layout = layout();
        ServiceGroup group = ServiceGroup.newBuilder()
                .setName("Lobby")
                .setPlatform("paper")
                .setVersion("1.20")
                .build();
        ServiceProvisioner provisioner = provisioner(layout, group, name -> false,
                (name, used) -> 1, definition -> {
                });

        ServiceLifecycleException error = assertThrows(ServiceLifecycleException.class,
                () -> provisioner.start("Lobby"));

        assertTrue(error.getMessage().contains("Plattform-Jar fehlt"), error.getMessage());
        assertTrue(error.getMessage().contains(layout.platforms().toString()), error.getMessage());
    }

    @Test
    void ambiguousPlatformJarsRequireAVersion() throws Exception {
        CloudLayout layout = layout();
        Files.createFile(layout.platforms().resolve("paper-1.20.jar"));
        Files.createFile(layout.platforms().resolve("paper-1.21.jar"));
        ServiceGroup group = ServiceGroup.newBuilder()
                .setName("Lobby")
                .setPlatform("paper")
                .build();
        ServiceProvisioner provisioner = provisioner(layout, group, name -> false,
                (name, used) -> 1, definition -> {
                });

        ServiceLifecycleException error = assertThrows(ServiceLifecycleException.class,
                () -> provisioner.start("Lobby"));

        assertTrue(error.getMessage().contains("Mehrere Plattform-Jars"), error.getMessage());
    }

    @Test
    void nameAllocationSkipsUsedNumbers() throws Exception {
        CloudLayout layout = layout();
        Files.createFile(layout.platforms().resolve("paper.jar"));
        ServiceGroup group = ServiceGroup.newBuilder()
                .setName("Lobby")
                .setPlatform("paper")
                .build();
        AtomicReference<ServiceDefinition> started = new AtomicReference<>();
        ServiceProvisioner provisioner = new ServiceProvisioner(
                name -> Optional.of(group),
                (name, used) -> {
                    assertTrue(used.test(1));
                    assertFalse(used.test(2));
                    return 2;
                },
                name -> name.equals("Lobby-1"),
                definition -> {
                    started.set(definition);
                    return info(definition);
                },
                (id, force) -> {
                    throw new AssertionError("stop nicht erwartet");
                },
                layout, CloudConfig.defaults());

        provisioner.start("Lobby");

        assertEquals("Lobby-2", started.get().name());
    }

    @Test
    void luckPermsIsInstalledBeforeTheServiceStarts() throws Exception {
        CloudLayout layout = layout();
        Files.createFile(layout.platforms().resolve("paper.jar"));
        ServiceGroup group = ServiceGroup.newBuilder()
                .setName("Lobby")
                .setPlatform("paper")
                .build();
        List<String> installed = new java.util.ArrayList<>();
        AtomicReference<ServiceDefinition> started = new AtomicReference<>();
        ServiceProvisioner provisioner = new ServiceProvisioner(
                name -> Optional.of(group),
                (name, used) -> 1,
                name -> false,
                definition -> {
                    started.set(definition);
                    return info(definition);
                },
                (id, force) -> {
                    throw new AssertionError("stop nicht erwartet");
                },
                layout, CloudConfig.defaults(),
                (platform, version) -> null,
                (installedGroup, directory, mongo, redis) -> {
                    installed.add(installedGroup.getName());
                    try {
                        Files.createDirectories(directory);
                        Files.writeString(directory.resolve("luckperms.txt"), "bereit");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

        provisioner.start("Lobby");

        assertEquals(List.of("Lobby"), installed);
        assertTrue(Files.isRegularFile(started.get().directory().resolve("luckperms.txt")),
                "LuckPerms liegt vor dem Start im Service-Ordner");
    }

    @Test
    void aBrokenLuckPermsInstallDoesNotBlockTheStart() throws Exception {
        CloudLayout layout = layout();
        Files.createFile(layout.platforms().resolve("paper.jar"));
        ServiceGroup group = ServiceGroup.newBuilder()
                .setName("Lobby")
                .setPlatform("paper")
                .build();
        ServiceProvisioner provisioner = new ServiceProvisioner(
                name -> Optional.of(group),
                (name, used) -> 1,
                name -> false,
                definition -> info(definition),
                (id, force) -> {
                    throw new AssertionError("stop nicht erwartet");
                },
                layout, CloudConfig.defaults(),
                (platform, version) -> null,
                (installedGroup, directory, mongo, redis) -> {
                    throw new IllegalStateException("keine Verbindung");
                });

        assertEquals("Lobby-1", provisioner.start("Lobby").getName(),
                "ohne LuckPerms startet der Service trotzdem");
    }

    @Test
    void unknownGroupsAndStopDelegationAreHandled() throws Exception {
        CloudLayout layout = layout();
        ServiceGroup group = ServiceGroup.newBuilder()
                .setName("Lobby")
                .setPlatform("paper")
                .build();
        AtomicReference<ServiceId> stopped = new AtomicReference<>();
        ServiceProvisioner provisioner = new ServiceProvisioner(
                name -> Optional.empty(),
                (name, used) -> 1,
                name -> false,
                definition -> info(definition),
                (id, force) -> {
                    stopped.set(id);
                    return ServiceInfo.newBuilder().setId(id.toString()).build();
                },
                layout, CloudConfig.defaults());

        assertThrows(ServiceLifecycleException.class, () -> provisioner.start("missing"));

        ServiceId id = ServiceId.random();
        provisioner.stop(id, false);
        assertEquals(id, stopped.get());
    }

    private CloudLayout layout() throws IOException {
        CloudLayout layout = new CloudLayout(temp.resolve("cloud"));
        layout.create();
        return layout;
    }

    private ServiceProvisioner provisioner(CloudLayout layout, ServiceGroup group,
                                           java.util.function.Predicate<String> nameUsed,
                                           java.util.function.BiFunction<String, java.util.function.IntPredicate, Integer> names,
                                           java.util.function.Consumer<ServiceDefinition> started) {
        return new ServiceProvisioner(
                name -> Optional.of(group),
                names,
                nameUsed,
                definition -> {
                    started.accept(definition);
                    return info(definition);
                },
                (id, force) -> {
                    throw new AssertionError("stop nicht erwartet");
                },
                layout, CloudConfig.defaults());
    }

    private static ServiceInfo info(ServiceDefinition definition) {
        return ServiceInfo.newBuilder()
                .setId(definition.id().toString())
                .setName(definition.name())
                .setGroup(definition.group().getName())
                .build();
    }
}
