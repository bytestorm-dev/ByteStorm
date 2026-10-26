package dev.bytestorm.core.module;

import dev.bytestorm.api.ByteStormApi;
import dev.bytestorm.api.CloudModule;
import dev.bytestorm.api.module.ModuleContext;
import dev.bytestorm.core.mongo.model.ModuleConfig;
import dev.bytestorm.proto.ModuleInfo;
import dev.bytestorm.proto.ModuleState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleManagerTest {

    private final Map<String, ModuleConfig> stored = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();

    private ModuleManager manager;

    @BeforeEach
    void setUp() {
        manager = new ModuleManager(new FakeStore(), Path.of("cloud", "config", "modules"),
                () -> api, () -> List.of(new FakeModule("rest", false, calls), new FakeModule("kaputt", true, calls)));
    }

    @Test
    void discoveredModulesAreListedWithoutBeingEnabled() {
        List<String> names = manager.list().stream().map(ModuleInfo::getName).toList();

        assertEquals(List.of("kaputt", "rest"), names);
        assertFalse(manager.isEnabled("rest"));
        assertEquals(ModuleState.LOADED, manager.get("rest").orElseThrow().getState());
        assertEquals("1.0", manager.get("rest").orElseThrow().getVersion());
        assertTrue(calls.isEmpty(), "Nur das Finden lädt noch nichts");
    }

    @Test
    void enableLoadsOnceAndPersistsTheState() {
        manager.enable("rest").join();

        assertEquals(List.of("load:rest", "enable:rest"), calls);
        assertTrue(manager.isEnabled("rest"));
        assertEquals(ModuleState.ENABLED, manager.get("rest").orElseThrow().getState());
        assertTrue(stored.get("rest").enabled());

        manager.enable("rest").join();
        assertEquals(List.of("load:rest", "enable:rest"), calls, "onLoad läuft nur einmal");
    }

    @Test
    void disableStopsTheModuleAndPersistsTheState() {
        manager.enable("rest").join();
        manager.disable("rest").join();

        assertEquals(List.of("load:rest", "enable:rest", "disable:rest"), calls);
        assertFalse(manager.isEnabled("rest"));
        assertFalse(stored.get("rest").enabled());
    }

    @Test
    void aFailingModuleStaysIsolated() {
        CompletionException error = assertThrows(CompletionException.class, () -> manager.enable("kaputt").join());

        assertTrue(error.getCause().getMessage().contains("kaputt"), error.getCause().getMessage());
        assertEquals(ModuleState.FAILED, manager.get("kaputt").orElseThrow().getState());
        assertFalse(manager.isEnabled("kaputt"));
        assertFalse(stored.containsKey("kaputt"), "ein fehlgeschlagenes Modul wird nicht als aktiviert gespeichert");
    }

    @Test
    void startLoadsTheModulesEnabledInTheStore() {
        stored.put("rest", new ModuleConfig("rest", true, Map.of()));
        stored.put("fehlt", new ModuleConfig("fehlt", true, Map.of()));

        manager.start();

        assertEquals(List.of("load:rest", "enable:rest"), calls);
        assertEquals(Set.of("fehlt", "kaputt", "rest"), manager.list().stream()
                .map(ModuleInfo::getName).collect(java.util.stream.Collectors.toSet()));
        // Das aktivierte, aber nicht vorhandene Modul bleibt sichtbar und deaktiviert.
        assertEquals(ModuleState.DISABLED, manager.get("fehlt").orElseThrow().getState());
    }

    @Test
    void enablingAModuleWithoutImplementationIsReported() {
        CompletionException error = assertThrows(CompletionException.class, () -> manager.enable("fehlt").join());

        assertTrue(error.getCause().getMessage().contains("Kein Modul 'fehlt'"), error.getCause().getMessage());
    }

    @Test
    void theContextPointsToTheModuleDirectoryAndItsSettings() {
        stored.put("rest", new ModuleConfig("rest", false, Map.of("port", "8080")));

        manager.enable("rest").join();

        assertEquals(List.of("load:rest", "enable:rest"), calls);
        assertEquals(Path.of("cloud", "config", "modules", "rest").toAbsolutePath(),
                FakeModule.lastContext.dataDirectory());
        assertEquals(Map.of("port", "8080"), FakeModule.lastContext.settings());
    }

    @Test
    void invalidNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> manager.enable("Rest!"));
        assertThrows(IllegalArgumentException.class, () -> manager.enable(" "));
    }

    private static final ByteStormApi api = new ByteStormApi() {
        @Override
        public dev.bytestorm.api.service.Services services() {
            throw new UnsupportedOperationException("im Test nicht benötigt");
        }

        @Override
        public dev.bytestorm.api.player.Players players() {
            throw new UnsupportedOperationException("im Test nicht benötigt");
        }

        @Override
        public dev.bytestorm.api.group.Groups groups() {
            throw new UnsupportedOperationException("im Test nicht benötigt");
        }

        @Override
        public dev.bytestorm.api.event.Events events() {
            throw new UnsupportedOperationException("im Test nicht benötigt");
        }

        @Override
        public dev.bytestorm.api.template.Templates templates() {
            throw new UnsupportedOperationException("im Test nicht benötigt");
        }

        @Override
        public dev.bytestorm.api.module.Modules modules() {
            throw new UnsupportedOperationException("im Test nicht benötigt");
        }
    };

    /** Modul, das jeden Lebenszyklus-Schritt protokolliert und bei Bedarf scheitert. */
    private static final class FakeModule implements CloudModule {

        private static ModuleContext lastContext;

        private final String name;
        private final boolean fail;
        private final List<String> calls;

        private FakeModule(String name, boolean fail, List<String> calls) {
            this.name = name;
            this.fail = fail;
            this.calls = calls;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String version() {
            return "1.0";
        }

        @Override
        public void onLoad(ModuleContext context) {
            lastContext = context;
            calls.add("load:" + name);
        }

        @Override
        public void onEnable() {
            calls.add("enable:" + name);
            if (fail) {
                throw new IllegalStateException(name + " ist kaputt");
            }
        }

        @Override
        public void onDisable() {
            calls.add("disable:" + name);
        }
    }

    private final class FakeStore implements ModuleStore {

        @Override
        public List<ModuleConfig> findAll() {
            return List.copyOf(stored.values());
        }

        @Override
        public Optional<ModuleConfig> find(String name) {
            return Optional.ofNullable(stored.get(name));
        }

        @Override
        public void save(ModuleConfig config) {
            stored.put(config.name(), config);
        }
    }
}
