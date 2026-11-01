package dev.bytestorm.launcher.wizard;

import dev.bytestorm.core.config.MongoSettings;
import dev.bytestorm.core.mongo.MongoProvider;
import dev.bytestorm.core.mongo.model.ModuleConfig;
import dev.bytestorm.core.mongo.repository.ModuleConfigRepository;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Objects;

/**
 * Modul-Konfigurationen für den Erststart (§5.1, §16).
 *
 * <p>Der Wizard fragt die Einstellungen ab, die ein Modul zum Starten braucht —
 * für das REST-Modul etwa den API-Key, ohne den es absichtlich nicht lauscht. Hier
 * landen sie in der Collection {@code modules_config}, aus der der
 * {@code ModuleManager} beim Start liest. Damit braucht niemand die Datenbank von
 * Hand zu bearbeiten, und die Cloud startet das Modul direkt mit.
 *
 * <p>Ein erneuter Lauf überschreibt die Konfiguration der abgefragten Module: Die
 * Antworten des Betreibers sind die neue Wahrheit.
 */
@UtilityClass
public final class DefaultModules {

    /** Name des REST-Moduls (§11). */
    public static final String REST = "rest";

    /** Einstellungsname des API-Keys im REST-Modul. */
    public static final String REST_API_KEY = "apiKey";

    /** Einstellungsname der Netzadresse im REST-Modul. */
    public static final String REST_HOST = "host";

    /** Einstellungsname des Ports im REST-Modul. */
    public static final String REST_PORT = "port";

    /**
     * Standardadresse des REST-Moduls: nur lokal. Muss zu
     * {@code RestSettings.DEFAULT_HOST} im Modul passen — dort steht derselbe
     * Default für den Betrieb ohne Wizard (§20).
     */
    public static final String REST_DEFAULT_HOST = "127.0.0.1";

    /** Standardport des REST-Moduls, wie {@code RestSettings.DEFAULT_PORT}. */
    public static final int REST_DEFAULT_PORT = 8080;

    /** Kürzere Keys gelten als unsicher, wie {@code RestSettings.MIN_KEY_LENGTH} (§20). */
    public static final int REST_MIN_KEY_LENGTH = 8;

    /**
     * Legt die Modul-Konfigurationen an oder ersetzt sie.
     *
     * @param settings MongoDB-Verbindung
     * @param modules  Konfigurationen der aktivierten Module
     */
    public static void create(MongoSettings settings, List<ModuleConfig> modules) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(modules, "modules");
        if (modules.isEmpty()) {
            return;
        }
        try (MongoProvider mongo = MongoProvider.connect(settings)) {
            ModuleConfigRepository repository = new ModuleConfigRepository(mongo);
            for (ModuleConfig module : modules) {
                repository.upsert(module);
            }
        }
    }
}
