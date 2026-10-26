package dev.bytestorm.api;

import dev.bytestorm.api.event.Events;
import dev.bytestorm.api.group.Groups;
import dev.bytestorm.api.module.Modules;
import dev.bytestorm.api.player.Players;
import dev.bytestorm.api.service.Services;
import dev.bytestorm.api.template.Templates;

/**
 * Fassade der öffentlichen API. Alle Zugriffe laufen über diese Schnittstelle,
 * damit REST-Modul, Discord-Modul und Bridge-Plugins dieselbe Sicht auf die
 * Cloud haben (Konzept §15, §18).
 */
public interface ByteStormApi {

    /**
     * @return Zugriff auf Services (live aus Redis)
     */
    Services services();

    /**
     * @return Zugriff auf Spieler (live aus Redis, Historie aus MongoDB)
     */
    Players players();

    /**
     * @return Zugriff auf Gruppen-Definitionen
     */
    Groups groups();

    /**
     * @return Event-Abonnements für Proto-Events
     */
    Events events();

    /**
     * @return Zugriff auf Template-Metadaten
     */
    Templates templates();

    /**
     * @return Zugriff auf optionale Module
     */
    Modules modules();
}
