package dev.bytestorm.plugins.proxy.message;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    @TempDir
    Path temp;

    @Test
    void readsTheShippedLanguagesAndReplacesPlaceholders() throws Exception {
        Messages english = Messages.load("en", temp);
        assertEquals("en", english.language());
        assertEquals("&bByteStorm &7· &f3&7/&f20",
                english.text("tablist.header", Map.of("players", "3", "max", "20")));

        Messages german = Messages.load("de", temp);
        assertEquals("de", german.language());
        assertTrue(german.text("maintenance.kick", Map.of()).contains("Wartung"));
    }

    @Test
    void unknownLanguageFallsBackToEnglish() throws Exception {
        Messages messages = Messages.load("fr", temp);

        assertEquals("en", messages.language(), "ohne Sprachdatei gilt Englisch");
        assertTrue(messages.find("tablist.header").isPresent());
    }

    @Test
    void overridesInTheDataDirectoryWin() throws Exception {
        Path messages = Files.createDirectories(temp.resolve("messages"));
        Files.writeString(messages.resolve("en.json"), "{ \"tablist.footer\": \"Nur hier: {players}\" }");

        Messages loaded = Messages.load("en", messages);

        assertEquals("Nur hier: 7", loaded.find("tablist.footer", Map.of("players", "7")).orElseThrow());
        assertTrue(loaded.find("tablist.header").isPresent(),
                "nicht überschriebene Schlüssel kommen weiter aus dem Plugin-Jar");
    }

    @Test
    void aLanguageThatOnlyHasAnOverrideStillWorks() throws Exception {
        Path messages = Files.createDirectories(temp.resolve("messages"));
        Files.writeString(messages.resolve("fr.json"), "{ \"tablist.footer\": \"Amusez-vous\" }");

        Messages loaded = Messages.load("fr", messages);

        assertEquals("fr", loaded.language());
        assertEquals("Amusez-vous", loaded.text("tablist.footer", Map.of()));
        assertTrue(loaded.find("tablist.header").isPresent(), "fehlende Texte kommen aus dem Englischen");
    }

    @Test
    void missingKeysStayVisibleInsteadOfRenderingNothing() throws Exception {
        Messages messages = Messages.load("en", temp);

        assertEquals("gibt.es.nicht", messages.text("gibt.es.nicht", Map.of()));
        assertTrue(messages.find("gibt.es.nicht").isEmpty());
    }

    @Test
    void everyShippedLanguageHasTheSameKeys() throws Exception {
        Set<String> english = Messages.load("en", temp).keys();
        Set<String> german = Messages.load("de", temp).keys();

        assertEquals(english, german, "eine fehlende Übersetzung fällt sonst erst im Betrieb auf");
    }

    @Test
    void brokenFilesAreReportedWithTheirName() throws Exception {
        Path messages = Files.createDirectories(temp.resolve("messages"));
        Files.writeString(messages.resolve("en.json"), "{ \"tablist.header\": 42 }");

        IOException error = assertThrows(IOException.class, () -> Messages.load("en", messages));

        assertTrue(error.getMessage().contains("tablist.header"), error.getMessage());
    }

    @Test
    void unknownPlaceholdersStayUntouched() {
        assertEquals("Hallo {unbekannt}, 3 Spieler",
                Messages.replace("Hallo {unbekannt}, {players} Spieler", Map.of("players", "3")));
    }
}
