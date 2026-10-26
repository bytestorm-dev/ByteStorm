package dev.bytestorm.api.group;

import dev.bytestorm.proto.ServiceGroup;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Verwaltung der Service-Gruppen (Konzept §9.1).
 */
public interface Groups {

    /**
     * @return alle Gruppen
     */
    Collection<ServiceGroup> all();

    /**
     * @param name Gruppenname
     * @return die Gruppe, falls vorhanden
     */
    Optional<ServiceGroup> get(String name);

    /**
     * Legt eine Gruppe an.
     *
     * @param group Definition
     * @return Future, das nach dem Persistieren abgeschlossen ist
     */
    CompletableFuture<Void> create(ServiceGroup group);

    /**
     * Überschreibt eine bestehende Gruppe.
     *
     * @param group Definition
     * @return Future, das nach dem Persistieren abgeschlossen ist
     */
    CompletableFuture<Void> update(ServiceGroup group);

    /**
     * Löscht eine Gruppe.
     *
     * @param name Gruppenname
     * @return Future, das nach dem Löschen abgeschlossen ist
     */
    CompletableFuture<Void> delete(String name);

    /**
     * Schaltet den Wartungsmodus einer Gruppe um (§14).
     *
     * @param name        Gruppenname
     * @param maintenance {@code true} aktiviert den Wartungsmodus
     * @return Future, das nach dem Umschalten abgeschlossen ist
     */
    CompletableFuture<Void> setMaintenance(String name, boolean maintenance);

    /**
     * Skaliert eine Gruppe manuell auf die gewünschte Anzahl Services (§11).
     *
     * @param name           Gruppenname
     * @param targetServices gewünschte Anzahl
     * @return Future, das nach dem Anstoßen abgeschlossen ist
     */
    CompletableFuture<Void> scale(String name, int targetServices);
}
