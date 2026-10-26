package dev.bytestorm.core.player;

import dev.bytestorm.api.event.Subscription;
import dev.bytestorm.core.event.EventBus;
import dev.bytestorm.core.mongo.repository.PlayerRepository;
import dev.bytestorm.core.redis.PlayerStore;
import dev.bytestorm.proto.PlayerInfo;
import dev.bytestorm.proto.PlayerJoined;
import dev.bytestorm.proto.PlayerLeft;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hält den Live-State der Spieler und die Historie (§7, §8, §14).
 *
 * <p>Die Proxies veröffentlichen {@code PlayerJoined}/{@code PlayerLeft}.
 * Beim Join landet der Spieler in {@code bs:players} und die Historie wird
 * angelegt; beim Leave wird der Eintrag entfernt, {@code lastSeen} aktualisiert
 * und die Sitzungsdauer zur Spielzeit addiert.
 *
 * <p>Die Sitzungsdauer lebt nur im Speicher dieses Prozesses. Nach einem
 * Cloud-Neustart fehlt sie für bereits laufende Sitzungen; der Live-State wird
 * dann beim nächsten Join wieder aufgebaut.
 */
@Slf4j
public final class PlayerRegistry implements AutoCloseable {

    private final PlayerStore store;
    private final PlayerRepository repository;
    private final Clock clock;
    private final Map<UUID, Instant> sessions = new ConcurrentHashMap<>();

    private final Subscription joined;
    private final Subscription left;

    /**
     * @param events     Event-Bus
     * @param store      Live-State in Redis
     * @param repository Historie in MongoDB
     */
    public PlayerRegistry(EventBus events, PlayerStore store, PlayerRepository repository) {
        this(events, store, repository, Clock.systemUTC());
    }

    PlayerRegistry(EventBus events, PlayerStore store, PlayerRepository repository, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(events, "events");
        this.joined = events.subscribe(PlayerJoined.class, this::onJoined);
        this.left = events.subscribe(PlayerLeft.class, this::onLeft);
    }

    private void onJoined(PlayerJoined event) {
        PlayerInfo player = event.getPlayer();
        UUID uuid = parse(player);
        if (uuid == null) {
            return;
        }
        Instant now = clock.instant();
        sessions.put(uuid, now);
        store.put(player);
        repository.recordPresence(player, now);
    }

    private void onLeft(PlayerLeft event) {
        PlayerInfo player = event.getPlayer();
        UUID uuid = parse(player);
        if (uuid == null) {
            return;
        }
        Instant now = clock.instant();
        store.remove(player.getUuid());
        repository.recordPresence(player, now);
        Instant start = sessions.remove(uuid);
        if (start != null) {
            repository.addPlaytime(uuid, Duration.between(start, now));
        }
    }

    /**
     * @return Anzahl der in diesem Prozess laufenden Sitzungen
     */
    public int sessionCount() {
        return sessions.size();
    }

    private static UUID parse(PlayerInfo player) {
        try {
            return UUID.fromString(player.getUuid());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("Spieler-Event mit ungültiger UUID: '{}'", player.getUuid());
            return null;
        }
    }

    @Override
    public void close() {
        joined.unsubscribe();
        left.unsubscribe();
        sessions.clear();
    }
}
