package dev.bytestorm.core.health;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Ergebnis eines vollständigen Healthchecks.
 *
 * <p>{@link #up()} ist abgeleitet und nicht speicherbar: Der Bericht ist nur
 * dann gesund, wenn alle geprüften Bestandteile gesund sind. Die Reihenfolge der
 * Bestandteile entspricht der Registrierung, damit CLI und REST stabil ausgeben.
 *
 * @param checkedAt  Beginn der Prüfung
 * @param latency    Gesamtdauer der Prüfung
 * @param components Ergebnis je Bestandteil, nach Name abrufbar
 */
public record HealthReport(Instant checkedAt, Duration latency, Map<String, ComponentHealth> components) {

    /**
     * @param checkedAt  Beginn der Prüfung
     * @param latency    Gesamtdauer, {@code null} wird zu {@link Duration#ZERO}
     * @param components Ergebnisse in Registrierungsreihenfolge
     */
    public HealthReport {
        Objects.requireNonNull(checkedAt, "checkedAt");
        latency = latency == null ? Duration.ZERO : latency;
        components = Collections.unmodifiableMap(
                new LinkedHashMap<>(components == null ? Map.of() : components));
    }

    /**
     * @return {@code true}, wenn alle Bestandteile gesund sind
     */
    public boolean up() {
        return components.values().stream().allMatch(ComponentHealth::healthy);
    }

    /**
     * @param name Name des Bestandteils
     * @return Ergebnis, falls geprüft
     */
    public Optional<ComponentHealth> component(String name) {
        return Optional.ofNullable(components.get(name));
    }

    /**
     * @return alle nicht verfügbaren Bestandteile
     */
    public List<ComponentHealth> unhealthy() {
        return components.values().stream()
                .filter(component -> !component.healthy())
                .toList();
    }
}
