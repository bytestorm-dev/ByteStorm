package dev.bytestorm.core.mongo.model;

import java.util.Map;
import java.util.Objects;

/**
 * Konfiguration eines optionalen Moduls (§8, §16).
 *
 * @param name     Modulname, z. B. {@code rest}
 * @param enabled  aktiviert
 * @param settings freie Einstellungen des Moduls
 */
public record ModuleConfig(String name, boolean enabled, Map<String, String> settings) {

    /**
     * @param name     Modulname
     * @param enabled  aktiviert
     * @param settings Einstellungen, {@code null} wird zu einer leeren Map
     */
    public ModuleConfig {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name darf nicht leer sein");
        }
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }
}
