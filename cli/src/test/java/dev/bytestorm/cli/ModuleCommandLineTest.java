package dev.bytestorm.cli;

import dev.bytestorm.api.module.Modules;
import dev.bytestorm.proto.ModuleInfo;
import dev.bytestorm.proto.ModuleState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleCommandLineTest {

    private final List<String> calls = new java.util.ArrayList<>();

    private Map<String, ModuleInfo> modules;
    private ModuleCommandLine commandLine;

    @BeforeEach
    void setUp() {
        modules = new LinkedHashMap<>();
        modules.put("discord", info("discord", "1.0", false, ModuleState.DISABLED));
        modules.put("rest", info("rest", "1.0", true, ModuleState.ENABLED));
        commandLine = new ModuleCommandLine(new FakeModules());
    }

    @Test
    void listShowsStateAndVersion() {
        String output = commandLine.execute(new String[]{"module", "list"});

        assertTrue(output.startsWith("Name\tStatus\tVersion"), output);
        assertTrue(output.contains("discord\tdeaktiviert (DISABLED)\t1.0"), output);
        assertTrue(output.contains("rest\taktiviert (ENABLED)\t1.0"), output);
        // Alphabetisch, nicht in Einfügereihenfolge.
        assertTrue(output.indexOf("discord") < output.indexOf("rest"), output);
    }

    @Test
    void listWithoutModulesExplainsWhereTheyComeFrom() {
        modules.clear();

        String output = commandLine.execute(new String[]{"module", "list"});

        assertTrue(output.contains("Keine Module gefunden"), output);
        assertTrue(output.contains("lib/"), output);
    }

    @Test
    void infoShowsTheDetailsOfOneModule() {
        String output = commandLine.execute(new String[]{"module", "info", "rest"});

        assertTrue(output.contains("Name: rest"), output);
        assertTrue(output.contains("Aktiviert: true"), output);
        assertTrue(output.contains("Version: 1.0"), output);
    }

    @Test
    void enableAndDisableGoThroughTheFacade() {
        assertEquals("Modul aktiviert: discord", commandLine.execute(new String[]{"module", "enable", "discord"}));
        assertEquals("Modul deaktiviert: rest", commandLine.execute(new String[]{"module", "disable", "rest"}));
        assertEquals(List.of("enable:discord", "disable:rest"), calls);
        assertTrue(modules.get("discord").getEnabled(), "Der Zustand kommt aus der Fassade");
        assertFalse(modules.get("rest").getEnabled());
    }

    @Test
    void enablingAnUnknownModuleReportsTheError() {
        String output = commandLine.execute(new String[]{"module", "enable", "unbekannt"});

        assertTrue(output.startsWith("Fehler: "), output);
        assertTrue(output.contains("unbekannt"), output);
    }

    @Test
    void unknownModuleAndMissingNameAreReported() {
        assertTrue(commandLine.execute(new String[]{"module", "info", "fehlt"}).contains("Unbekanntes Modul"));
        assertTrue(commandLine.execute(new String[]{"module", "enable"}).contains("Modulname fehlt"));
        assertTrue(commandLine.execute(new String[]{"module", "quark"}).contains("Unbekanntes Modul-Kommando"));
        assertTrue(commandLine.execute(new String[]{"module", "help"}).contains("module disable"));
    }

    private static ModuleInfo info(String name, String version, boolean enabled, ModuleState state) {
        return ModuleInfo.newBuilder()
                .setName(name)
                .setVersion(version)
                .setEnabled(enabled)
                .setState(state)
                .build();
    }

    private final class FakeModules implements Modules {

        @Override
        public Collection<ModuleInfo> list() {
            return List.copyOf(modules.values());
        }

        @Override
        public Optional<ModuleInfo> get(String name) {
            return Optional.ofNullable(modules.get(name));
        }

        @Override
        public boolean isEnabled(String name) {
            return get(name).map(ModuleInfo::getEnabled).orElse(false);
        }

        @Override
        public CompletableFuture<Void> enable(String name) {
            return toggle(name, true);
        }

        @Override
        public CompletableFuture<Void> disable(String name) {
            return toggle(name, false);
        }

        private CompletableFuture<Void> toggle(String name, boolean enabled) {
            ModuleInfo current = modules.get(name);
            if (current == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Kein Modul '" + name + "' im Classpath (lib/) gefunden"));
            }
            calls.add((enabled ? "enable:" : "disable:") + name);
            modules.put(name, current.toBuilder().setEnabled(enabled)
                    .setState(enabled ? ModuleState.ENABLED : ModuleState.DISABLED).build());
            return CompletableFuture.completedFuture(null);
        }
    }
}
