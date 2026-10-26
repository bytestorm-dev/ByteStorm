package dev.bytestorm.api.player;

import dev.bytestorm.proto.PlayerInfo;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Zugriff auf Spieler. Live-Daten kommen aus Redis, die Spielzeit-Historie
 * aus MongoDB (Konzept §8, §15).
 */
public interface Players {

    /**
     * @return alle aktuell verbundenen Spieler
     */
    Collection<PlayerInfo> online();

    /**
     * @return alle Spieler inklusive Offline-Historie
     */
    Collection<PlayerInfo> all();

    /**
     * @param uuid Spieler-UUID
     * @return der Spieler, falls er der Cloud bekannt ist
     */
    Optional<PlayerInfo> byUuid(UUID uuid);

    /**
     * @param uuid Spieler-UUID als String
     * @return der Spieler, falls er der Cloud bekannt ist
     */
    default Optional<PlayerInfo> byUuid(String uuid) {
        return byUuid(UUID.fromString(uuid));
    }

    /**
     * @param name Spielername (case-insensitive)
     * @return der Spieler, falls er der Cloud bekannt ist
     */
    Optional<PlayerInfo> byName(String name);

    /**
     * Verbindet einen Spieler mit einem Service.
     *
     * @param uuid    Spieler-UUID
     * @param service Service-Name
     * @return Future, das nach dem Routing abgeschlossen ist
     */
    CompletableFuture<Void> connect(UUID uuid, String service);

    /**
     * Trennt die Verbindung eines Spielers.
     *
     * @param uuid   Spieler-UUID
     * @param reason Begründung, wird dem Spieler angezeigt
     * @return Future, das nach dem Kick abgeschlossen ist
     */
    CompletableFuture<Void> kick(UUID uuid, String reason);

    /**
     * @param uuid Spieler-UUID
     * @return gesamte Spielzeit, {@link Duration#ZERO} für unbekannte Spieler
     */
    CompletableFuture<Duration> playtime(UUID uuid);
}
