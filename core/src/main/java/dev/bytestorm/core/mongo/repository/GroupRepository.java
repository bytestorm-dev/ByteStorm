package dev.bytestorm.core.mongo.repository;

import dev.bytestorm.core.mongo.MongoProvider;
import dev.bytestorm.core.mongo.collection.GroupCollection;
import dev.bytestorm.core.mongo.entity.GroupEntity;
import dev.bytestorm.core.mongo.mapping.ServiceGroupMapper;
import dev.bytestorm.proto.ServiceGroup;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Gruppen-Verwaltung über das en2do-Repository {@link GroupCollection} (§8, §9.1).
 *
 * <p>Erfüllt {@link GroupStore}, damit der {@code GroupManager} gegen die
 * Schnittstelle arbeiten kann.
 */
public final class GroupRepository implements GroupStore {

    private final GroupCollection collection;

    /**
     * @param provider verbundener Mongo-Provider
     */
    public GroupRepository(MongoProvider provider) {
        this.collection = provider.repository(GroupCollection.class);
    }

    /**
     * @return alle Gruppen, alphabetisch nach Name
     */
    @Override
    public List<ServiceGroup> findAll() {
        return collection.findAll().stream()
                .map(ServiceGroupMapper::toProto)
                .sorted(Comparator.comparing(ServiceGroup::getName))
                .toList();
    }

    /**
     * @param name Gruppenname
     * @return die Gruppe, falls vorhanden
     */
    @Override
    public Optional<ServiceGroup> find(String name) {
        return Optional.ofNullable(collection.findFirstById(name)).map(ServiceGroupMapper::toProto);
    }

    /**
     * Legt die Gruppe an oder ersetzt sie vollständig.
     *
     * @param group Definition
     */
    @Override
    public void upsert(ServiceGroup group) {
        collection.save(ServiceGroupMapper.toEntity(group));
    }

    /**
     * @param name Gruppenname
     * @return {@code true}, wenn eine Gruppe gelöscht wurde
     */
    @Override
    public boolean delete(String name) {
        return collection.deleteById(name);
    }

    /**
     * @param name        Gruppenname
     * @param maintenance neuer Zustand
     * @return {@code true}, wenn eine Gruppe aktualisiert wurde
     */
    @Override
    public boolean setMaintenance(String name, boolean maintenance) {
        GroupEntity entity = collection.findFirstById(name);
        if (entity == null) {
            return false;
        }
        entity.setMaintenance(maintenance);
        return collection.save(entity);
    }
}
