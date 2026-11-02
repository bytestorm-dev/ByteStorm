package dev.bytestorm.plugins.lobby.message;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    @Test
    void beideSprachenHabenDieselbenSchluessel() throws IOException {
        Messages german = Messages.load("de", null);
        Messages english = Messages.load("en", null);

        assertEquals(english.keys(), german.keys());
        assertFalse(german.keys().isEmpty());
    }

    @Test
    void verschachtelteObjekteWerdenZuPunktSchluesseln() throws IOException {
        Messages german = Messages.load("de", null);

        assertTrue(german.find("menu.entry.group").isPresent());
        assertTrue(german.find("command.usage-create").isPresent());
    }

    @Test
    void alleTexteSindGefuellt() throws IOException {
        Messages german = Messages.load("de", null);

        for (String key : german.keys()) {
            assertFalse(german.text(key).isBlank(), "leerer Text für " + key);
        }
    }

    @Test
    void unbekannteSpracheFaelltAufEnglischZurueck() throws IOException {
        Messages messages = Messages.load("xx", null);

        assertEquals(Messages.DEFAULT_LANGUAGE, messages.language());
        assertEquals("1 server", messages.text("display.servers-one"));
    }

    @Test
    void fehlenderSchluesselLiefertDenSchluessel() throws IOException {
        Messages german = Messages.load("de", null);

        assertEquals("gibt.es.nicht", german.text("gibt.es.nicht"));
    }

    @Test
    void platzhalterWerdenErsetzt() throws IOException {
        Messages german = Messages.load("de", null);

        assertEquals("2 Server", german.text("display.servers", Map.of("count", "2")));
        assertEquals("<dark_aqua>%x% bleibt", Messages.replace("<dark_aqua>%x% bleibt", Map.of("y", "1")));
    }

    @Test
    void prefixedSetztDenPraefixDavor() throws IOException {
        Messages german = Messages.load("de", null);

        String text = german.prefixed("command.deleted", Map.of("id", "3"));

        assertTrue(text.startsWith(german.text("prefix")));
        assertTrue(text.endsWith("Anzeige <white>#3</white> entfernt.</green>"));
    }

    @Test
    void ueberschreibungAusDemDatenordnerGewinnt(@TempDir Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("de.json"), """
                {
                  "messages": {
                    "display": { "offline": "<red>Niemand da</red>" }
                  }
                }
                """, StandardCharsets.UTF_8);

        Messages german = Messages.load("de", directory);

        assertEquals("<red>Niemand da</red>", german.text("display.offline"));
        // Nicht überschriebene Schlüssel kommen weiter aus dem Jar.
        assertEquals("1 Server", german.text("display.servers-one"));
    }

    @Test
    void ueberschreibungOhneMessagesObjektGehtAuch(@TempDir Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("en.json"), "{\"display\": {\"offline\": \"<red>Empty</red>\"}}",
                StandardCharsets.UTF_8);

        Messages english = Messages.load("en", directory);

        assertEquals("<red>Empty</red>", english.text("display.offline"));
    }
}
