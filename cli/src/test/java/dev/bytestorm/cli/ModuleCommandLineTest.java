package dev.bytestorm.cli;

import dev.bytestorm.api.module.AccessGrant;
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

    /** Entfernt Tabellenabstände: Zellen bleiben durch genau ein Leerzeichen getrennt. */
    private static String plain(String text) {
        return String.join("\n", text.lines()
                .map(line -> line.strip().replaceAll("\\s+", " "))
                .toList());
    }

    private final List<String> calls = new java.util.ArrayList<>();
    private final Map<String, Map<String, String>> settings = new LinkedHashMap<>();
    private final Map<String, List<AccessGrant>> grants = new LinkedHashMap<>();

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

        String text = plain(output);
        assertTrue(text.startsWith("Name Aktiviert Zustand Version Zugriff"), text);
        assertTrue(text.contains("discord nein DISABLED 1.0"), text);
        assertTrue(text.contains("rest ja ENABLED 1.0"), text);
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
        assertTrue(output.contains("Status: ENABLED"), output);
        assertTrue(output.contains("Aktiviert: ja"), output);
        assertTrue(output.contains("Version: 1.0"), output);
    }

    @Test
    void accessIsSummarizedInTheListAndDetailedInInfo() {
        grants.put("rest", List.of(
                new AccessGrant("apiKey", true, List.of("*")),
                new AccessGrant("monitoring", false, List.of("monitoring.read", "services.read"))));

        String list = plain(commandLine.execute(new String[]{"module", "list"}));
        assertTrue(list.contains("rest ja ENABLED 1.0 2 Keys, 1× voll"), list);
        assertTrue(list.contains("discord nein DISABLED 1.0 —"),
                "ohne Rechte bleibt die Spalte leer: " + list);

        String info = commandLine.execute(new String[]{"module", "info", "rest"});
        assertTrue(info.contains("Zugriff: apiKey"), info);
        assertTrue(info.contains("[*]"), "der volle Zugriff zeigt seine Wildcard: " + info);
        assertTrue(info.contains("Zugriff: monitoring"), info);
        assertTrue(info.contains("monitoring.read, services.read"), info);
        assertFalse(info.contains("geheim"), "kein Geheimnis in der Anzeige: " + info);
    }

    @Test
    void moduleInfoNamesTheEndpointsBehindEveryScope() {
        grants.put("rest", List.of(
                new AccessGrant("apiKey", true, List.of("*")),
                new AccessGrant("monitoring", false, List.of("monitoring.read"))));

        String output = plain(commandLine.execute(new String[]{"module", "info", "rest"}));

        assertTrue(output.contains("je Scope die freigegebenen Endpunkte"), output);
        assertTrue(output.contains("monitoring.read GET /health, GET /nodes/status, GET /metrics"),
                output);
        assertTrue(output.contains("services.write POST /services/start, POST /services/stop"),
                output);
        assertTrue(output.contains("players.write POST /players/connect, POST /players/kick"),
                output);
    }

    @Test
    void moduleInfoWithoutRightsShowsNoScopeMenu() {
        grants.put("discord", List.of());

        String output = plain(commandLine.execute(new String[]{"module", "info", "discord"}));

        assertFalse(output.contains("GET /services"), output);
        assertFalse(output.contains("je Scope"), output);
    }

    @Test
    void accessPrintsAMatrixOfEveryKeyAndItsScopes() {
        grants.put("rest", List.of(
                new AccessGrant("apiKey", true, List.of("*")),
                new AccessGrant("monitoring", false, List.of("monitoring.read", "services.read"))));
        grants.put("discord", List.of(new AccessGrant("bot", false, List.of("players.read"))));

        String output = plain(commandLine.execute(new String[]{"module", "access"}));

        assertTrue(output.startsWith("Modul Zugang Scopes"), output);
        assertTrue(output.contains("discord bot players.read"), output);
        assertTrue(output.contains("rest apiKey *"), output);
        assertTrue(output.contains("rest monitoring monitoring.read, services.read"), output);
        assertTrue(output.indexOf("discord") < output.indexOf("rest"),
                "Module stehen alphabetisch: " + output);
    }

    @Test
    void accessCanBeNarrowedToOneModule() {
        grants.put("rest", List.of(new AccessGrant("apiKey", true, List.of("*"))));
        grants.put("discord", List.of(new AccessGrant("bot", false, List.of("players.read"))));

        String output = plain(commandLine.execute(new String[]{"module", "access", "rest"}));

        assertTrue(output.contains("rest apiKey *"), output);
        assertFalse(output.contains("discord"), output);
    }

    @Test
    void accessWithoutEntriesOrWithAnUnknownModuleSaysSo() {
        String empty = commandLine.execute(new String[]{"module", "access"});
        assertTrue(empty.contains("Keine Zugänge hinterlegt"), empty);

        String none = commandLine.execute(new String[]{"module", "access", "discord"});
        assertTrue(none.contains("hat keine Zugänge hinterlegt"), none);

        String unknown = commandLine.execute(new String[]{"module", "access", "fehlt"});
        assertTrue(unknown.contains("Unbekanntes Modul"), unknown);
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
    void settingsAreShownWithSecretsRedacted() {
        commandLine.execute(new String[]{"module", "set", "rest", "apiKey", "geheim-geheim-1234"});
        commandLine.execute(new String[]{"module", "set", "rest", "port", "8099"});

        String output = commandLine.execute(new String[]{"module", "settings", "rest"});

        String text = plain(output);
        assertTrue(text.startsWith("Einstellung Wert"), text);
        assertTrue(text.contains("apiKey ***"), text);
        assertFalse(text.contains("geheim-geheim-1234"), "der Key erscheint nie im Klartext: " + text);
        assertTrue(text.contains("port 8099"), text);
    }

    @Test
    void settingAValueSaysWhenItTakesEffect() {
        String running = commandLine.execute(new String[]{"module", "set", "rest", "apiKey", "geheim-geheim-1234"});
        assertTrue(running.contains("Neustart der Cloud"), running);

        String stopped = commandLine.execute(new String[]{"module", "set", "discord", "botToken", "geheim"});
        assertTrue(stopped.contains("module enable discord"), stopped);

        assertEquals(List.of("set:rest.apiKey", "set:discord.botToken"), calls);
        assertEquals(Map.of("apiKey", "geheim-geheim-1234"), settings.get("rest"));
    }

    @Test
    void incompleteSettingsCommandsAreReported() {
        assertTrue(commandLine.execute(new String[]{"module", "set", "rest", "apiKey"}).contains("Verwendung"));
        assertTrue(commandLine.execute(new String[]{"module", "set", "fehlt", "apiKey", "wert"})
                .startsWith("Fehler: "));
        assertTrue(commandLine.execute(new String[]{"module", "settings"}).contains("Modulname fehlt"));
        assertTrue(commandLine.execute(new String[]{"module", "settings", "lobby"})
                .contains("keine Einstellungen"));
    }

    @Test
    void unknownModuleAndMissingNameAreReported() {
        assertTrue(commandLine.execute(new String[]{"module", "info", "fehlt"}).contains("Unbekanntes Modul"));
        assertTrue(commandLine.execute(new String[]{"module", "enable"}).contains("Modulname fehlt"));
        assertTrue(commandLine.execute(new String[]{"module", "quark"}).contains("Unbekanntes Modul-Kommando"));
        assertTrue(commandLine.execute(new String[]{"module", "help"}).contains("module disable"));
        assertTrue(commandLine.execute(new String[]{"module", "help"}).contains("module set"));
        assertTrue(commandLine.execute(new String[]{"module", "help"}).contains("module access"));
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
        public Map<String, String> settings(String name) {
            return Map.copyOf(settings.getOrDefault(name, Map.of()));
        }

        @Override
        public Collection<AccessGrant> access(String name) {
            return List.copyOf(grants.getOrDefault(name, List.of()));
        }

        @Override
        public void setSetting(String name, String key, String value) {
            if (!modules.containsKey(name)) {
                throw new IllegalArgumentException("Unbekanntes Modul: " + name);
            }
            settings.computeIfAbsent(name, module -> new LinkedHashMap<>()).put(key, value);
            calls.add("set:" + name + "." + key);
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
