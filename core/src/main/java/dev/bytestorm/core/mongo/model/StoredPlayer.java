package dev.bytestorm.core.mongo.model;

import dev.bytestorm.proto.PlayerInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Spieler-Datensatz aus der {@code players}-Collection (§8). Enthält die
 * Historie, die im Live-State ({@link PlayerInfo}) nicht vorkommt.
 *
 * @param uuid        Spieler-UUID
 * @param name        letzter bekannter Name
 * @param playtime    gesamte Spielzeit
 * @param firstSeen   erster Join
 * @param lastSeen    letzter Join/Heartbeat
 * @param lastService zuletzt bekannter Service
 */
public record StoredPlayer(
        UUID uuid,
        String name,
        Duration playtime,
        Instant firstSeen,
        Instant lastSeen,
        String lastService) {

    /**
     * @param uuid        Spieler-UUID
     * @param name        Name
     * @param playtime    Spielzeit
     * @param firstSeen   erster Join
     * @param lastSeen    letzter Join
     * @param lastService zuletzt bekannter Service, darf {@code null} sein
     */
    public StoredPlayer {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        playtime = playtime == null ? Duration.ZERO : playtime;
        firstSeen = firstSeen == null ? Instant.EPOCH : firstSeen;
        lastSeen = lastSeen == null ? Instant.EPOCH : lastSeen;
        lastService = lastService == null || lastService.isBlank() ? null : lastService;
    }

    /**
     * @return Live-Sicht auf den Spieler
     */
    public PlayerInfo toProto() {
        PlayerInfo.Builder builder = PlayerInfo.newBuilder()
                .setUuid(uuid.toString())
                .setName(name);
        if (lastService != null) {
            builder.setServer(lastService);
        }
        return builder.build();
    }
}
