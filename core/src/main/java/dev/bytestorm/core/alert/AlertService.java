package dev.bytestorm.core.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Zustand der Alarme (§20): meldet jeden Alarm genau einmal und seine
 * Behebung genau einmal.
 *
 * <p>Der Service bekommt keine Ereignisse, sondern in jedem Prüflauf die
 * <em>aktuell gültige Menge</em> ({@link #evaluate}). Daraus ergibt sich der
 * Unterschied zum letzten Lauf: neue Alarme werden gemeldet, verschwundene als
 * behoben. Dieses Modell ist absichtlich zustandsbehaftet statt ereignisbasiert
 * — es kann nicht „hängen“, wenn ein Ereignis verloren geht, und flattert nicht,
 * solange die Lage unverändert ist.
 *
 * <p>Thread-safe: Ein Prüflauf und ein gleichzeitiges Abfragen der aktiven
 * Alarme sehen nie einen halben Zustand.
 */
public final class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final List<AlertSink> sinks;
    private final Map<Alert, Instant> active = new LinkedHashMap<>();

    /**
     * @param sinks Ziele in Melde-Reihenfolge; eine leere Liste ist erlaubt
     */
    public AlertService(Collection<? extends AlertSink> sinks) {
        this.sinks = List.copyOf(sinks == null ? List.of() : sinks);
    }

    /**
     * Vergleicht die aktuelle Lage mit der letzten.
     *
     * @param current alle gerade gültigen Alarme; Duplikate werden ignoriert
     * @return die neu gemeldeten und die abgeklungenen Alarme
     */
    public Changes evaluate(Collection<Alert> current) {
        return evaluate(current, Instant.now());
    }

    /**
     * Wie {@link #evaluate(Collection)}, mit vorgegebener Zeit für Tests.
     *
     * @param current alle gerade gültigen Alarme
     * @param now     Zeitpunkt der Prüfung
     * @return die Änderungen
     */
    public synchronized Changes evaluate(Collection<Alert> current, Instant now) {
        Objects.requireNonNull(now, "now");
        List<Alert> present = new ArrayList<>(
                new LinkedHashSet<>(current == null ? List.of() : current));
        Set<Alert> next = new HashSet<>(present);

        List<Alert> resolved = new ArrayList<>();
        for (Alert alert : List.copyOf(active.keySet())) {
            if (!next.contains(alert)) {
                active.remove(alert);
                resolved.add(alert);
                for (AlertSink sink : sinks) {
                    emit(sink, alert, false);
                }
            }
        }

        List<Alert> raised = new ArrayList<>();
        for (Alert alert : present) {
            if (active.containsKey(alert)) {
                continue;
            }
            active.put(alert, now);
            raised.add(alert);
            for (AlertSink sink : sinks) {
                emit(sink, alert, true);
            }
        }
        return new Changes(raised, resolved);
    }

    /**
     * @return alle gerade aktiven Alarme in Melde-Reihenfolge
     */
    public synchronized List<Alert> active() {
        return List.copyOf(active.keySet());
    }

    private static void emit(AlertSink sink, Alert alert, boolean raised) {
        try {
            if (raised) {
                sink.raised(alert);
            } else {
                sink.resolved(alert);
            }
        } catch (RuntimeException e) {
            // Ein Ziel, das nicht schreiben kann, darf die anderen nicht aufhalten.
            log.warn("Alarmziel {} konnte '{}' nicht melden: {}",
                    sink.getClass().getSimpleName(), alert.source(), e.getMessage());
        }
    }

    /**
     * Ergebnis eines Prüflaufs.
     *
     * @param raised   neu aktive Alarme
     * @param resolved nicht mehr gültige Alarme
     */
    public record Changes(List<Alert> raised, List<Alert> resolved) {

        /**
         * @param raised   neu aktive Alarme
         * @param resolved abgeklungene Alarme
         */
        public Changes {
            raised = raised == null ? List.of() : List.copyOf(raised);
            resolved = resolved == null ? List.of() : List.copyOf(resolved);
        }

        /**
         * @return {@code true}, wenn sich nichts geändert hat
         */
        public boolean isEmpty() {
            return raised.isEmpty() && resolved.isEmpty();
        }
    }
}
