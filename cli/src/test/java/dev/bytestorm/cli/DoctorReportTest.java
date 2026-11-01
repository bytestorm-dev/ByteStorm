package dev.bytestorm.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DoctorReportTest {

    private final boolean previous = Theme.enabled();

    @AfterEach
    void restoreColors() {
        Theme.setEnabled(previous);
    }

    @Test
    void rendersEveryAreaWithSeverityAndSummary() {
        Theme.setEnabled(false);

        String text = DoctorReport.render(List.of(
                new Diagnostics.Finding(Diagnostics.Severity.OK, "Pfade", "8 Verzeichnisse vorhanden"),
                new Diagnostics.Finding(Diagnostics.Severity.WARNING, "Ports", "Lobby-1 läuft auf 20000"),
                new Diagnostics.Finding(Diagnostics.Severity.ERROR, "Forwarding",
                        "Proxy-1: player-info-forwarding-mode ist 'none' statt 'modern'")));

        assertTrue(text.contains("Diagnose"), text);
        assertTrue(text.contains("3 Prüfungen"), text);
        assertTrue(text.contains("1 Warnung"), text);
        assertTrue(text.contains("1 Fehler"), text);
        assertTrue(text.contains("Pfade"), text);
        assertTrue(text.contains("8 Verzeichnisse vorhanden"), text);
        assertTrue(text.contains("'none' statt 'modern'"), text);
    }

    @Test
    void emptyReportSaysEverythingIsFine() {
        Theme.setEnabled(false);

        String text = DoctorReport.render(List.of());

        assertTrue(text.contains("0 Prüfungen in Ordnung"), text);
    }
}
