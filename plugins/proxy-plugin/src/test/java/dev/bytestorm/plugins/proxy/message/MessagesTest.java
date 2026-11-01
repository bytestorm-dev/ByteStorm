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
        assertEquals("<aqua><bold>ByteStorm</bold></aqua> <dark_gray>·</dark_gray> "
                        + "<white>3</white><dark_gray>/</dark_gray><white>20</white>",
                english.text("tablist.header", Map.of("players", "3", "max", "20")));

        Messages german = Messages.load("de", temp);
        assertEquals("de", german.language());
        assertTrue(german.text("server-join-cancel-because-maintenance", Map.of()).contains("Wartung"));
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
        Files.writeString(messages.resolve("en.json"), """
                { "messages": { "tablist": { "footer": "<gray>Nur hier: %players%</gray>" } } }
                """);

        Messages loaded = Messages.load("en", messages);

        assertEquals("<gray>Nur hier: 7</gray>",
                loaded.find("tablist.footer", Map.of("players", "7")).orElseThrow(),
                "ein eigener Block gewinnt gegen das Jar");
        assertTrue(loaded.find("tablist.header").isPresent(),
                "nicht überschriebene Schlüssel kommen weiter aus dem Plugin-Jar");
    }

    @Test
    void aLanguageThatOnlyHasAnOverrideStillWorks() throws Exception {
        Path messages = Files.createDirectories(temp.resolve("messages"));
        Files.writeString(messages.resolve("fr.json"), "{ \"messages\": { \"tablist.footer\": \"Amusez-vous\" } }");

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
    void nestedBlocksBecomeDottedKeys() throws Exception {
        Messages messages = Messages.load("en", temp);

        assertTrue(messages.find("cloud.status.header").isPresent(),
                "cloud.status.header kommt aus dem verschachtelten Block");
        assertTrue(messages.find("cloud.service.restartCrashed.none").isPresent(),
                "auch drei Ebenen werden zu Punkt-Schlüsseln");
    }

    @Test
    void multilineMessagesKeepTheirLineBreaks() throws Exception {
        String kick = Messages.load("de", temp).text("server-join-cancel-because-maintenance", Map.of());

        assertEquals(3, kick.split("\n").length, kick);
        assertTrue(kick.contains("Wartungsmodus"), kick);
    }

    @Test
    void brokenFilesAreReportedWithTheirName() throws Exception {
        Path messages = Files.createDirectories(temp.resolve("messages"));
        Files.writeString(messages.resolve("en.json"),
                "{ \"messages\": { \"tablist\": { \"header\": 42 } } }");

        IOException error = assertThrows(IOException.class, () -> Messages.load("en", messages));

        assertTrue(error.getMessage().contains("tablist.header"), error.getMessage());
    }

    @Test
    void unknownPlaceholdersStayUntouched() {
        assertEquals("Hallo %unbekannt%, 3 Spieler",
                Messages.replace("Hallo %unbekannt%, %players% Spieler", Map.of("players", "3")));
    }
}
