package dev.bytestorm.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeTest {

    private final boolean previous = Theme.enabled();

    @AfterEach
    void restore() {
        Theme.forcePlain(false);
        Theme.setEnabled(previous);
    }

    @Test
    void colorsOnlyOnARealTerminal() {
        assertTrue(Theme.allowed("xterm-256color", null, "xterm-256color", null));
        assertFalse(Theme.allowed("dumb", null, "xterm", null));
        assertFalse(Theme.allowed("xterm", null, "dumb", null));
        assertFalse(Theme.allowed("xterm", "1", "xterm", null));
        assertFalse(Theme.allowed("xterm", null, "xterm", "never"));
        assertTrue(Theme.allowed("dumb", "1", "dumb", "always"));
    }

    @Test
    void forcedPlainBeatsEverything() {
        Theme.forcePlain(true);

        assertFalse(Theme.allowed("xterm", null, "xterm", "always"));
        Theme.setEnabled(true);
        assertFalse(Theme.enabled(), "forcePlain darf nicht überschrieben werden");

        Theme.forcePlain(false);
        Theme.setEnabled(true);
        assertTrue(Theme.enabled());
    }

    @Test
    void plainTextStaysPlain() {
        Theme.setEnabled(false);

        assertEquals("Fertig", Theme.ok("Fertig"));
        assertEquals("Fehler: kaputt", Theme.fail("Fehler: kaputt"));
        assertEquals("Hinweis", Theme.warn("Hinweis"));
        assertEquals("Name: Wert", Theme.field("Name", "Wert"));
    }

    @Test
    void coloredTextKeepsItsContent() {
        Theme.setEnabled(true);

        assertEquals("✔ Fertig", Theme.stripAnsi(Theme.ok("Fertig")));
        assertEquals("✖ Fehler: kaputt", Theme.stripAnsi(Theme.fail("Fehler: kaputt")));
        assertEquals("! Hinweis", Theme.stripAnsi(Theme.warn("Hinweis")));
        assertEquals("Name: Wert", Theme.stripAnsi(Theme.field("Name", "Wert")));
    }

    @Test
    void helpBoldsOnlyTheCommandWord() {
        Theme.setEnabled(false);
        assertEquals("service list  alle", Theme.help("service list  alle"));

        Theme.setEnabled(true);
        String line = Theme.help("service list  alle\n  --flag  Hinweis");
        assertEquals("service list  alle", Theme.stripAnsi(line.lines().findFirst().orElse("")));
        assertEquals("  --flag  Hinweis",
                Theme.stripAnsi(line.lines().skip(1).findFirst().orElse("")));
    }
}
