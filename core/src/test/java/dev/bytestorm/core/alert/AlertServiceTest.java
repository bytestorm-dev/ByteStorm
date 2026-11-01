package dev.bytestorm.core.alert;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertServiceTest {

    private final List<String> events = new ArrayList<>();
    private final AlertSink recording = new AlertSink() {
        @Override
        public void raised(Alert alert) {
            events.add("+" + alert.source());
        }

        @Override
        public void resolved(Alert alert) {
            events.add("-" + alert.source());
        }
    };

    @Test
    void reportsNewAlertOnlyOnce() {
        AlertService service = new AlertService(List.of(recording));
        List<Alert> current = List.of(Alert.critical("health:redis", "Redis antwortet nicht"));

        AlertService.Changes first = service.evaluate(current);
        assertEquals(1, first.raised().size());
        assertTrue(first.resolved().isEmpty());

        AlertService.Changes second = service.evaluate(current);
        assertTrue(second.isEmpty(), "gleiche Lage darf nicht erneut melden");
        assertEquals(1, service.active().size());
        assertEquals(List.of("+health:redis"), events);
    }

    @Test
    void reportsRecoveryOnlyOnce() {
        AlertService service = new AlertService(List.of(recording));
        List<Alert> current = List.of(Alert.warning("service:Lobby-1", "abgestürzt"));
        service.evaluate(current);

        AlertService.Changes changes = service.evaluate(List.of());
        assertEquals(1, changes.resolved().size());
        assertTrue(service.evaluate(List.of()).isEmpty());
        assertTrue(service.active().isEmpty());
        assertEquals(List.of("+service:Lobby-1", "-service:Lobby-1"), events);
    }

    @Test
    void changingSeverityIsANewAlert() {
        AlertService service = new AlertService(List.of(recording));
        service.evaluate(List.of(Alert.warning("disk", "Speicher knapp")));
        AlertService.Changes changes = service.evaluate(
                List.of(Alert.critical("disk", "Speicher knapp")));

        assertEquals(1, changes.raised().size());
        assertEquals(1, changes.resolved().size());
        assertEquals(List.of("+disk", "-disk", "+disk"), events);
    }

    @Test
    void duplicatesInOneSnapshotAreCollapsed() {
        AlertService service = new AlertService(List.of(recording));
        Alert alert = Alert.critical("group:Proxy", "DEGRADED");
        AlertService.Changes changes = service.evaluate(List.of(alert, alert));

        assertEquals(1, changes.raised().size());
        assertEquals(List.of("+group:Proxy"), events);
    }

    @Test
    void brokenSinkDoesNotStopTheOthers() {
        List<Alert> seen = new ArrayList<>();
        AlertService service = new AlertService(List.of(
                alert -> {
                    throw new IllegalStateException("Datei voll");
                },
                alert -> seen.add(alert)));

        service.evaluate(List.of(Alert.critical("mongo", "weg")));
        assertEquals(1, seen.size());
    }

    @Test
    void rejectsBlankSource() {
        assertThrows(IllegalArgumentException.class, () -> Alert.warning(" ", "text"));
    }

    @Test
    void keepsRaisedOrder() {
        AlertService service = new AlertService(List.of(recording));
        service.evaluate(List.of(Alert.warning("b", "zwei"), Alert.critical("a", "eins")),
                Instant.parse("2026-11-01T12:00:00Z"));

        assertEquals(List.of("b", "a"), service.active().stream().map(Alert::source).toList());
    }
}
