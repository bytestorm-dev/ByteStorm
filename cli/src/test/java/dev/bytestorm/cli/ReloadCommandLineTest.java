package dev.bytestorm.cli;

import dev.bytestorm.controller.group.GroupManager;
import dev.bytestorm.core.config.ConfigException;
import dev.bytestorm.core.config.ConfigService;
import dev.bytestorm.core.config.PortSettings;
import dev.bytestorm.core.mongo.repository.GroupStore;
import dev.bytestorm.core.redis.GroupCache;
import dev.bytestorm.proto.ServiceGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReloadCommandLineTest {

    @TempDir
    Path temp;

    private final Map<String, ServiceGroup> stored = new LinkedHashMap<>();
    private final Map<String, ServiceGroup> cached = new LinkedHashMap<>();

    private ReloadCommandLine commandLine;

    @BeforeEach
    void setUp() throws IOException, ConfigException {
        Path file = writeConfig("mongodb://localhost:27017");
        stored.put("Lobby", ServiceGroup.newBuilder().setName("Lobby").setPlatform("paper").build());
        GroupManager groups = new GroupManager(new FakeStore(), new FakeCache(),
                new PortSettings(25565, 30000), name -> true);
        commandLine = new ReloadCommandLine(ConfigService.load(file), groups);
    }

    @Test
    void reloadLoadsConfigAndFillsTheGroupCache() {
        String output = commandLine.execute(new String[]{"reload"});

        assertTrue(output.contains("Konfiguration neu geladen"), output);
        assertTrue(output.contains("Gruppen-Cache neu geladen: 1 Gruppe"), output);
        assertEquals(Set.of("Lobby"), cached.keySet());
    }

    @Test
    void reloadGroupsOnlyTouchesTheCache() {
        String output = commandLine.execute(new String[]{"reload", "groups"});

        assertFalse(output.contains("Konfiguration"), output);
        assertTrue(output.contains("Gruppen-Cache neu geladen: 1 Gruppe"), output);
        assertEquals(Set.of("Lobby"), cached.keySet());
    }

    @Test
    void reloadConfigOnlyTouchesTheConfig() {
        String output = commandLine.execute(new String[]{"reload", "config"});

        assertTrue(output.contains("Konfiguration neu geladen"), output);
        assertFalse(output.contains("Gruppen-Cache"), output);
        assertTrue(cached.isEmpty(), "Der Cache bleibt beim reinen Config-Reload unangetastet");
    }

    @Test
    void changedConnectionDataAsksForARestartButStillReloadsTheCache() throws IOException {
        writeConfig("mongodb://localhost:27018");

        String output = commandLine.execute(new String[]{"reload"});

        assertTrue(output.contains("Neustart nötig"), output);
        assertTrue(output.contains("mongo"), output);
        assertTrue(output.contains("Gruppen-Cache neu geladen: 1 Gruppe"), output);
    }

    @Test
    void unknownTargetAndHelpAreReported() {
        assertTrue(commandLine.execute(new String[]{"reload", "quark"}).contains("Unbekanntes reload-Ziel"));
        assertTrue(commandLine.execute(new String[]{"reload", "help"}).contains("reload groups"));
    }

    private Path writeConfig(String mongoUri) throws IOException {
        Path directory = Files.createDirectories(temp.resolve("config"));
        Path file = directory.resolve("cloud.json");
        String json = """
                {
                  "mongo": {"uri": "%s", "database": "bytestorm"},
                  "redis": {"address": "redis://localhost:6379", "password": "geheim"},
                  "scaling": {"intervalSeconds": 5}
                }
                """.formatted(mongoUri);
        Files.writeString(file, json);
        return file;
    }

    private final class FakeStore implements GroupStore {

        @Override
        public List<ServiceGroup> findAll() {
            return List.copyOf(stored.values());
        }

        @Override
        public Optional<ServiceGroup> find(String name) {
            return Optional.ofNullable(stored.get(name));
        }

        @Override
        public void upsert(ServiceGroup group) {
            stored.put(group.getName(), group);
        }

        @Override
        public boolean delete(String name) {
            return stored.remove(name) != null;
        }

        @Override
        public boolean setMaintenance(String name, boolean maintenance) {
            ServiceGroup group = stored.get(name);
            if (group == null) {
                return false;
            }
            stored.put(name, group.toBuilder().setMaintenance(maintenance).build());
            return true;
        }
    }

    private final class FakeCache implements GroupCache {

        @Override
        public Optional<ServiceGroup> get(String name) {
            return Optional.ofNullable(cached.get(name));
        }

        @Override
        public void put(ServiceGroup group) {
            cached.put(group.getName(), group);
        }

        @Override
        public boolean invalidate(String name) {
            return cached.remove(name) != null;
        }

        @Override
        public void clear() {
            cached.clear();
        }
    }
}
