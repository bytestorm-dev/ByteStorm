package dev.bytestorm.core.mongo.mapping;

import dev.bytestorm.core.mongo.entity.PlayerEntity;
import dev.bytestorm.core.mongo.model.StoredPlayer;
import lombok.experimental.UtilityClass;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Abbildung {@link StoredPlayer} ↔ {@link PlayerEntity}. Zeiten werden als
 * Epochen-Millis gespeichert, damit keine Zusatz-Codecs nötig sind.
 */
@UtilityClass
public final class PlayerMapper {

    /**
     * @param player Domain-Modell
     * @return en2do-Entity
     */
    public static PlayerEntity toEntity(StoredPlayer player) {
        PlayerEntity entity = new PlayerEntity();
        entity.setUuid(player.uuid().toString());
        entity.setName(player.name());
        entity.setPlaytimeMillis(player.playtime().toMillis());
        entity.setFirstSeen(player.firstSeen().toEpochMilli());
        entity.setLastSeen(player.lastSeen().toEpochMilli());
        entity.setLastService(player.lastService());
        return entity;
    }

    /**
     * @param entity en2do-Entity
     * @return Domain-Modell
     * @throws IllegalStateException wenn die UUID im Dokument ungültig ist
     */
    public static StoredPlayer toDomain(PlayerEntity entity) {
        UUID uuid;
        try {
            uuid = UUID.fromString(String.valueOf(entity.getUuid()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Ungültige UUID im players-Dokument: " + entity.getUuid(), e);
        }
        return new StoredPlayer(
                uuid,
                nullToEmpty(entity.getName()),
                Duration.ofMillis(entity.getPlaytimeMillis()),
                Instant.ofEpochMilli(entity.getFirstSeen()),
                Instant.ofEpochMilli(entity.getLastSeen()),
                entity.getLastService());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
