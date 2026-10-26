package dev.bytestorm.core.mongo.repository;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import dev.bytestorm.core.mongo.MongoProvider;
import dev.bytestorm.core.mongo.collection.PlayerCollection;
import dev.bytestorm.core.mongo.entity.PlayerEntity;
import dev.bytestorm.core.mongo.mapping.PlayerMapper;
import dev.bytestorm.core.mongo.model.StoredPlayer;
import dev.bytestorm.proto.PlayerInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Spieler-Historie über das en2do-Repository {@link PlayerCollection} (§8).
 * Der Live-State liegt in Redis; hier steht nur, was einen Neustart überlebt.
 */
public final class PlayerRepository {

    private final PlayerCollection collection;

    /**
     * @param provider verbundener Mongo-Provider
     */
    public PlayerRepository(MongoProvider provider) {
        this.collection = provider.repository(PlayerCollection.class);
    }

    /**
     * @param uuid Spieler-UUID
     * @return Datensatz, falls vorhanden
     */
    public Optional<StoredPlayer> find(UUID uuid) {
        return Optional.ofNullable(collection.findFirstById(uuid.toString())).map(PlayerMapper::toDomain);
    }

    /**
     * Sucht case-insensitive nach Namen.
     *
     * @param name Spielername
     * @return Datensatz, falls vorhanden
     */
    public Optional<StoredPlayer> findByName(String name) {
        Pattern pattern = Pattern.compile("^" + Pattern.quote(name) + "$", Pattern.CASE_INSENSITIVE);
        return Optional.ofNullable(collection.findFirstByNameRegex(pattern)).map(PlayerMapper::toDomain);
    }

    /**
     * @return alle bekannten Spieler, alphabetisch nach Name
     */
    public List<StoredPlayer> findAll() {
        return collection.findAll().stream()
                .map(PlayerMapper::toDomain)
                .sorted(Comparator.comparing(StoredPlayer::name))
                .toList();
    }

    /**
     * Aktualisiert den Spieler beim Join: Name, letzter Service und Zeitstempel.
     * Beim ersten Kontakt werden {@code firstSeen} und Spielzeit angelegt.
     *
     * @param player Live-Information aus dem Proxy
     * @param now    Zeitpunkt des Joins
     */
    public void recordPresence(PlayerInfo player, Instant now) {
        PlayerEntity entity = collection.findFirstById(player.getUuid());
        if (entity == null) {
            entity = new PlayerEntity();
            entity.setUuid(player.getUuid());
            entity.setFirstSeen(now.toEpochMilli());
            entity.setPlaytimeMillis(0L);
        }
        entity.setName(player.getName());
        entity.setLastSeen(now.toEpochMilli());
        entity.setLastService(player.getServer());
        collection.save(entity);
    }

    /**
     * Addiert Spielzeit atomar über die native Collection ({@code $inc}); en2do
     * bietet für Inkremente keinen Operator.
     *
     * @param uuid     Spieler-UUID
     * @param duration Dauer
     */
    public void addPlaytime(UUID uuid, Duration duration) {
        collection.getNativeCollection()
                .updateOne(Filters.eq("_id", uuid.toString()), Updates.inc("playtimeMillis", duration.toMillis()));
    }

    /**
     * @return Anzahl bekannter Spieler
     */
    public long count() {
        return collection.countAll();
    }
}
