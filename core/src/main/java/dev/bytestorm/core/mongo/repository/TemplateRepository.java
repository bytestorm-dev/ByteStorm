package dev.bytestorm.core.mongo.repository;

import dev.bytestorm.core.mongo.MongoProvider;
import dev.bytestorm.core.mongo.collection.TemplateCollection;
import dev.bytestorm.core.mongo.mapping.TemplateMapper;
import dev.bytestorm.proto.TemplateInfo;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Template-Metadaten über das en2do-Repository {@link TemplateCollection} (§10).
 */
public final class TemplateRepository {

    private final TemplateCollection collection;

    /**
     * @param provider verbundener Mongo-Provider
     */
    public TemplateRepository(MongoProvider provider) {
        this.collection = provider.repository(TemplateCollection.class);
    }

    /**
     * @return alle Template-Metadaten, alphabetisch nach Name
     */
    public List<TemplateInfo> findAll() {
        return collection.findAll().stream()
                .map(TemplateMapper::toProto)
                .sorted(Comparator.comparing(TemplateInfo::getName))
                .toList();
    }

    /**
     * @param name Templatename
     * @return Metadaten, falls vorhanden
     */
    public Optional<TemplateInfo> find(String name) {
        return Optional.ofNullable(collection.findFirstById(name)).map(TemplateMapper::toProto);
    }

    /**
     * Legt die Metadaten an oder ersetzt sie.
     *
     * @param template Metadaten
     */
    public void upsert(TemplateInfo template) {
        collection.save(TemplateMapper.toEntity(template));
    }

    /**
     * @param name Templatename
     * @return {@code true}, wenn Metadaten gelöscht wurden
     */
    public boolean delete(String name) {
        return collection.deleteById(name);
    }
}
