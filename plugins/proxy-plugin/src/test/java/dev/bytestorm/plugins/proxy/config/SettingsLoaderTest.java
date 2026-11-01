package dev.bytestorm.plugins.proxy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsLoaderTest {

    private final SettingsLoader loader = new SettingsLoader();

    @Test
    void missingFileFallsBackToDefaults(@TempDir Path temp) throws Exception {
        ProxySettings settings = loader.load(temp.resolve("config.json"));

        assertEquals("en", settings.language());
        assertTrue(settings.tablist().enabled());
        assertEquals(5, settings.tablist().updateSeconds());
        assertFalse(settings.maintenance().enabled());
        assertEquals("bytestorm.maintenance.bypass", settings.maintenance().bypassPermission());
        assertTrue(settings.maintenance().allowedPlayers().isEmpty());
    }

    @Test
    void readsEveryFeatureFlag() {
        ProxySettings settings = loader.parse("""
                {
                  "language": "de",
                  "tablist": { "enabled": false, "updateSeconds": 30 },
                  "maintenance": {
                    "enabled": true,
                    "bypassPermission": "netz.team",
                    "allowedPlayers": ["Notch", "  jeb_  "]
                  }
                }
                """);

        assertEquals("de", settings.language());
        assertFalse(settings.tablist().enabled());
        assertEquals(30, settings.tablist().updateSeconds());
        assertTrue(settings.maintenance().enabled());
        assertEquals("netz.team", settings.maintenance().bypassPermission());
        assertEquals(List.of("Notch", "jeb_"), settings.maintenance().allowedPlayers());
    }

    @Test
    void readsTheCloudBlock() {
        ProxySettings settings = loader.parse("""
                {
                  "cloud": {
                    "enabled": true,
                    "baseUrl": "https://cloud.example.com/",
                    "apiKey": "  geheim-1234  ",
                    "permission": "netz.admin",
                    "timeoutSeconds": 25
                  }
                }
                """);

        assertTrue(settings.cloud().enabled());
        assertEquals("https://cloud.example.com", settings.cloud().baseUrl(),
                "ein Schrägstrich am Ende fällt weg");
        assertEquals("geheim-1234", settings.cloud().apiKey());
        assertEquals("netz.admin", settings.cloud().permission());
        assertEquals(25, settings.cloud().timeoutSeconds());
        assertTrue(settings.cloud().usable());
    }

    @Test
    void theTablistToggleHasItsOwnPermission() {
        ProxySettings defaults = loader.parse("{}");
        assertEquals("bytestorm.tablist.admin", defaults.tablist().permission());

        ProxySettings settings = loader.parse(
                "{ \"tablist\": { \"permission\": \"  netz.tablist  \" } }");
        assertEquals("netz.tablist", settings.tablist().permission());

        ProxySettings blank = loader.parse("{ \"tablist\": { \"permission\": \"   \" } }");
        assertEquals("bytestorm.tablist.admin", blank.tablist().permission(),
                "ein leerer Wert nutzt den Default");
    }

    @Test
    void cloudIsOffWithoutAKeyByDefault() {
        ProxySettings settings = loader.parse("{}");

        assertFalse(settings.cloud().enabled());
        assertFalse(settings.cloud().usable(), "ohne Key bleibt /cloud aus");
        assertEquals(ProxySettings.Cloud.DEFAULT_BASE_URL, settings.cloud().baseUrl());
        assertEquals("bytestorm.cloud.command", settings.cloud().permission());
        assertEquals(10, settings.cloud().timeoutSeconds());
    }

    @Test
    void anEnabledCloudWithoutKeyStaysUnusable() {
        ProxySettings settings = loader.parse("{ \"cloud\": { \"enabled\": true, \"apiKey\": \"  \" } }");

        assertTrue(settings.cloud().enabled(), "der Schalter bleibt stehen");
        assertFalse(settings.cloud().usable(), "aber ohne Key läuft nichts");
    }

    @Test
    void wrongCloudValuesNameTheField() {
        assertTrue(assertThrows(SettingsException.class,
                () -> loader.parse("{ \"cloud\": { \"baseUrl\": \"cloud:8080\" } }"))
                .getMessage().contains("baseUrl"), "ohne Schema keine Adresse");
        assertTrue(assertThrows(SettingsException.class,
                () -> loader.parse("{ \"cloud\": { \"timeoutSeconds\": 0 } }"))
                .getMessage().contains("timeoutSeconds"));
        assertTrue(assertThrows(SettingsException.class,
                () -> loader.parse("{ \"cloud\": { \"enabled\": \"ja\" } }"))
                .getMessage().contains("enabled"));
    }

    @Test
    void unknownFieldsAreToleratedSoNewerFilesStillLoad() {
        ProxySettings settings = loader.parse("""
                {
                  "language": "de",
                  "tablist": { "enabled": true, "updateSeconds": 5, "align": "center" },
                  "chat": { "enabled": true }
                }
                """);

        assertEquals("de", settings.language());
        assertTrue(settings.tablist().enabled());
    }

    @Test
    void wrongTypesNameTheField() {
        assertTrue(assertThrows(SettingsException.class,
                () -> loader.parse("{ \"tablist\": { \"updateSeconds\": \"oft\" } }"))
                .getMessage().contains("updateSeconds"));

        assertTrue(assertThrows(SettingsException.class,
                () -> loader.parse("{ \"maintenance\": { \"enabled\": \"ja\" } }"))
                .getMessage().contains("enabled"));

        assertTrue(assertThrows(SettingsException.class,
                () -> loader.parse("{ \"maintenance\": { \"allowedPlayers\": \"Notch\" } }"))
                .getMessage().contains("allowedPlayers"));
    }

    @Test
    void unusableValuesAreRejected() {
        assertThrows(SettingsException.class,
                () -> loader.parse("{ \"tablist\": { \"updateSeconds\": 0 } }"),
                "ein Intervall von 0 Sekunden würde dauerhaft schreiben");
        assertThrows(SettingsException.class, () -> loader.parse("{ \"language\": \"deutsch!\" }"));
        assertThrows(SettingsException.class, () -> loader.parse("[]"));
        assertThrows(SettingsException.class, () -> loader.parse("{"));
    }
}
