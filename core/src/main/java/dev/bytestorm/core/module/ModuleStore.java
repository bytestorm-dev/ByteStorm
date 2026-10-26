package dev.bytestorm.core.module;

import dev.bytestorm.core.mongo.model.ModuleConfig;

import java.util.List;
import java.util.Optional;

/**
 * Persistenz der Modul-Konfigurationen (§4, §16).
 *
 * <p>Wie {@code GroupStore} trennt die Schnittstelle den {@code ModuleManager} von
 * MongoDB, damit er ohne laufenden Server prüfbar bleibt. Umgesetzt von
 * {@link MongoModuleStore}.
 */
public interface ModuleStore {

    /**
     * @return alle Modul-Konfigurationen, alphabetisch nach Name
     */
    List<ModuleConfig> findAll();

    /**
     * @param name Modulname
     * @return Konfiguration, falls vorhanden
     */
    Optional<ModuleConfig> find(String name);

    /**
     * Legt die Konfiguration an oder ersetzt sie.
     *
     * @param config Konfiguration
     */
    void save(ModuleConfig config);
}
