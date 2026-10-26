package dev.bytestorm.core.mongo.repository;

import dev.bytestorm.core.mongo.MongoProvider;
import dev.bytestorm.core.mongo.collection.ModuleConfigCollection;
import dev.bytestorm.core.mongo.mapping.ModuleConfigMapper;
import dev.bytestorm.core.mongo.model.ModuleConfig;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Modul-Konfiguration über das en2do-Repository {@link ModuleConfigCollection} (§16).
 */
public final class ModuleConfigRepository {

    private final ModuleConfigCollection collection;

    /**
     * @param provider verbundener Mongo-Provider
     */
    public ModuleConfigRepository(MongoProvider provider) {
        this.collection = provider.repository(ModuleConfigCollection.class);
    }

    /**
     * @return alle Modul-Konfigurationen, alphabetisch nach Name
     */
    public List<ModuleConfig> findAll() {
        return collection.findAll().stream()
                .map(ModuleConfigMapper::toDomain)
                .sorted(Comparator.comparing(ModuleConfig::name))
                .toList();
    }

    /**
     * @param name Modulname
     * @return Konfiguration, falls vorhanden
     */
    public Optional<ModuleConfig> find(String name) {
        return Optional.ofNullable(collection.findFirstById(name)).map(ModuleConfigMapper::toDomain);
    }

    /**
     * Legt die Konfiguration an oder ersetzt sie.
     *
     * @param config Konfiguration
     */
    public void upsert(ModuleConfig config) {
        collection.save(ModuleConfigMapper.toEntity(config));
    }
}
