package dev.bytestorm.core.mongo.repository;

import dev.bytestorm.proto.ServiceGroup;

import java.util.List;
import java.util.Optional;

/**
 * Persistenz der Gruppen-Definitionen (§4, §9.1).
 *
 * <p>Die Schnittstelle trennt die Gruppen-Verwaltung von MongoDB, damit
 * {@code GroupManager} und seine Tests nicht von einem laufenden Server
 * abhängen. Umgesetzt wird sie von {@link GroupRepository}.
 */
public interface GroupStore {

    /**
     * @return alle Gruppen, alphabetisch nach Name
     */
    List<ServiceGroup> findAll();

    /**
     * @param name Gruppenname
     * @return die Gruppe, falls vorhanden
     */
    Optional<ServiceGroup> find(String name);

    /**
     * Legt die Gruppe an oder ersetzt sie vollständig.
     *
     * @param group Definition
     */
    void upsert(ServiceGroup group);

    /**
     * @param name Gruppenname
     * @return {@code true}, wenn eine Gruppe gelöscht wurde
     */
    boolean delete(String name);

    /**
     * @param name        Gruppenname
     * @param maintenance neuer Zustand
     * @return {@code true}, wenn eine Gruppe aktualisiert wurde
     */
    boolean setMaintenance(String name, boolean maintenance);
}
