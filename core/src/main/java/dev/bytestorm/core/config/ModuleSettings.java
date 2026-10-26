package dev.bytestorm.core.config;

/**
 * Optionale Module, die der Launcher einbindet (§5.2, §16, §21).
 *
 * @param rest    REST-Modul aktiv
 * @param discord Discord-Modul aktiv
 */
public record ModuleSettings(boolean rest, boolean discord) {
}
