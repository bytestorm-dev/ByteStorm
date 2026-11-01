package dev.bytestorm.api.health;

import java.util.List;
import java.util.Objects;

/**
 * Zustand der Cloud-Bestandteile, wie ihn ein Modul oder ein externer Client
 * sehen soll (§11, §18).
 *
 * <p>Die Cloud prüft MongoDB, Redis und die Runtime; ein Modul bekommt das
 * Ergebnis über {@link dev.bytestorm.api.ByteStormApi#health()}. {@code up} ist
 * nur dann {@code true}, wenn alle gemeldeten Bestandteile gesund sind — eine
 * leere Liste gilt bewusst als gesund, damit ein Client ohne Prüfungen nicht
 * dauerhaft „down“ meldet.
 *
 * @param up         {@code true}, wenn alle Bestandteile verfügbar sind
 * @param components Ergebnis je Bestandteil in Prüfungsreihenfolge
 */
public record CloudHealth(boolean up, List<Component> components) {

    /**
     * @param up         Gesamtzustand
     * @param components Ergebnisse, {@code null} wird zu einer leeren Liste
     */
    public CloudHealth {
        components = components == null ? List.of() : List.copyOf(components);
    }

    /**
     * Zustand ohne Prüfung, z. B. in Tests oder in einem Prozess, der keine
     * Healthcheck-Fassade hat.
     *
     * @param reason Begründung, warum nicht geprüft wurde
     * @return ein nicht gesunder Zustand mit einem einzigen Bestandteil
     */
    public static CloudHealth unavailable(String reason) {
        String detail = reason == null || reason.isBlank() ? "Healthcheck nicht verfügbar" : reason;
        return new CloudHealth(false, List.of(new Component("cloud", false, detail)));
    }

    /**
     * @return alle nicht verfügbaren Bestandteile
     */
    public List<Component> unhealthy() {
        return components.stream().filter(component -> !component.healthy()).toList();
    }

    /**
     * Ergebnis einer einzelnen Prüfung.
     *
     * @param name    Name des Bestandteils, z. B. {@code mongo}
     * @param healthy verfügbar
     * @param detail  {@code OK} oder die Fehlermeldung der Prüfung
     */
    public record Component(String name, boolean healthy, String detail) {

        /**
         * @param name    Name des Bestandteils
         * @param healthy verfügbar
         * @param detail  Meldung, {@code null} wird zu einem leeren String
         */
        public Component {
            Objects.requireNonNull(name, "name");
            detail = detail == null ? "" : detail;
        }
    }
}
