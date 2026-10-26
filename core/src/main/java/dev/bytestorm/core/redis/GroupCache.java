package dev.bytestorm.core.redis;

import dev.bytestorm.proto.ServiceGroup;

import java.util.Optional;

/**
 * Kurzlebiger Gruppen-Cache (§4, §7).
 *
 * <p>Der Cache beschleunigt wiederkehrende Zugriffe (Provisioner, CLI, REST) und
 * darf die Datenbank nie überstimmen: Einträge laufen über eine TTL ab, und jede
 * Änderung über den {@code GroupManager} verwirft den betroffenen Eintrag.
 * Umgesetzt von {@link RedisGroupCache}.
 */
public interface GroupCache {

    /**
     * @param name Gruppenname
     * @return gecachte Definition, falls vorhanden und nicht abgelaufen
     */
    Optional<ServiceGroup> get(String name);

    /**
     * @param group Gruppen-Definition
     */
    void put(ServiceGroup group);

    /**
     * Verwirft alle Einträge, damit der nächste Zugriff wieder aus dem Storage liest
     * (§3.1: {@code reload}).
     */
    void clear();

    /**
     * @param name Gruppenname
     * @return {@code true}, wenn ein Eintrag verworfen wurde
     */
    boolean invalidate(String name);
}
