package dev.bytestorm.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableTest {

    private final boolean previous = Theme.enabled();

    @AfterEach
    void restoreColors() {
        Theme.setEnabled(previous);
    }

    @Test
    void padsColumnsToTheWidestCell() {
        Theme.setEnabled(false);

        String output = Table.of("Name", "Port").row("Lobby", "25565").row("Hub-1", "25566").render();

        String[] lines = output.split(System.lineSeparator());
        assertEquals("Name   Port ", lines[0]);
        assertEquals("-".repeat(5) + "  " + "-".repeat(5), lines[1]);
        assertEquals("Lobby  25565", lines[2]);
        assertEquals("Hub-1  25566", lines[3]);
    }

    @Test
    void ignoresColorCodesWhenMeasuring() {
        Theme.setEnabled(true);

        String output = Table.of("Name", "Status")
                .row(Theme.bold("Lobby"), Theme.green("ONLINE"))
                .row("Hub-1", "STOPPED")
                .render();

        String header = Theme.stripAnsi(output.split(System.lineSeparator())[0]);
        assertEquals("Name   Status", header.stripTrailing());
        assertEquals("Lobby  ONLINE",
                Theme.stripAnsi(output.split(System.lineSeparator())[2]).stripTrailing());
    }

    @Test
    void numbersAreRightAligned() {
        Theme.setEnabled(false);

        String[] lines = Table.of("Name", "Spieler").row("Lobby", "3").row("Hub-1", "12").render()
                .split(System.lineSeparator());

        assertTrue(lines[2].endsWith("  3"), lines[2]);
        assertTrue(lines[3].endsWith(" 12"), lines[3]);
    }

    @Test
    void unicodeRuleOnlyOnAFancyTerminal() {
        Theme.setEnabled(false);
        assertTrue(Table.of("A").row("1").render().contains("-"), "ASCII-Trennlinie erwartet");

        Theme.setEnabled(true);
        assertTrue(Table.of("A").row("1").render().contains("─"), "Rahmenzeichen erwartet");
    }
}
