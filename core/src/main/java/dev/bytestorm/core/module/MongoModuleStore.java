package dev.bytestorm.core.module;

import dev.bytestorm.core.mongo.model.ModuleConfig;
import dev.bytestorm.core.mongo.repository.ModuleConfigRepository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ModuleStore} über das en2do-Repository {@code modules_config} (§16).
 */
public final class MongoModuleStore implements ModuleStore {

    private final ModuleConfigRepository repository;

    /**
     * @param repository Modul-Repository aus dem Mongo-Provider
     */
    public MongoModuleStore(ModuleConfigRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public List<ModuleConfig> findAll() {
        return repository.findAll();
    }

    @Override
    public Optional<ModuleConfig> find(String name) {
        return repository.find(name);
    }

    @Override
    public void save(ModuleConfig config) {
        repository.upsert(config);
    }
}
