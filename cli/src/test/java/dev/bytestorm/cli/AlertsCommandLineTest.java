package dev.bytestorm.cli;

import dev.bytestorm.core.alert.Alert;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertsCommandLineTest {

    private static final String[] COMMAND = {"alerts"};

    @Test
    void listsCriticalBeforeWarning() {
        AlertsCommandLine cli = new AlertsCommandLine(() -> List.of(
                Alert.warning("service:Lobby-1", "Service 'Lobby-1' ist abgestürzt"),
                Alert.critical("group:Proxy", "Gruppe 'Proxy' ist DEGRADED")));

        String output = cli.execute(COMMAND);

        assertTrue(output.contains("2 aktiv"), output);
        assertTrue(output.contains("1 kritisch"), output);
        assertTrue(output.contains("1 Warnung"), output);
        assertTrue(output.indexOf("group:Proxy") < output.indexOf("service:Lobby-1"),
                "kritische Alarme stehen oben");
        assertTrue(output.contains("ist DEGRADED"), output);
    }

    @Test
    void reportsWhenNothingIsActive() {
        String output = new AlertsCommandLine(List::of).execute(COMMAND);

        assertTrue(output.contains("Keine aktiven Alarme"), output);
        assertTrue(output.contains("logs/alerts.log"), output);
    }

    @Test
    void pluralisesWarningCount() {
        String output = new AlertsCommandLine(() -> List.of(
                Alert.warning("a", "erster"), Alert.warning("b", "zweiter")))
                .execute(COMMAND);

        assertTrue(output.contains("2 Warnungen"), output);
    }

    @Test
    void showHelpWhenAsked() {
        String output = new AlertsCommandLine(List::of).execute(new String[]{"alerts", "help"});

        assertTrue(output.contains("aktive Alarme"), output);
    }

    @Test
    void brokenSourceIsReportedInsteadOfThrown() {
        String output = new AlertsCommandLine(() -> {
            throw new IllegalStateException("Registry weg");
        }).execute(COMMAND);

        assertTrue(output.contains("Fehler: Registry weg"), output);
    }

    @Test
    void keepsTheCommandNameInTheUsage() {
        assertEquals("alerts", AlertsCommandLine.USAGE.lines().findFirst().orElseThrow().trim().split("\\s+")[0]);
    }
}
