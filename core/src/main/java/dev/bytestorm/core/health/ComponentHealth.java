package dev.bytestorm.core.health;

import java.util.Objects;

/**
 * Ergebnis einer einzelnen Prüfung.
 *
 * @param name    Name des Bestandteils, z. B. {@code mongo}
 * @param healthy verfügbar
 * @param detail  {@code OK} oder die Fehlermeldung der Prüfung
 */
public record ComponentHealth(String name, boolean healthy, String detail) {

    /**
     * @param name    Name des Bestandteils
     * @param healthy verfügbar
     * @param detail  Meldung, {@code null} wird zu einem leeren String
     */
    public ComponentHealth {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name darf nicht leer sein");
        }
        detail = detail == null ? "" : detail;
    }
}
