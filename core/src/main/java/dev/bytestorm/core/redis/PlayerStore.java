package dev.bytestorm.core.redis;

import dev.bytestorm.proto.PlayerInfo;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Live-State der Spieler unter {@code bs:players} (§7).
 */
public final class PlayerStore {

    private final RMap<String, PlayerInfo> players;

    /**
     * @param client verbundener Redisson-Client
     */
    public PlayerStore(RedissonClient client) {
        this.players = client.getMap(RedisKeys.PLAYERS);
    }

    /**
     * @param player Spieler mit UUID, Name, Proxy und Service
     */
    public void put(PlayerInfo player) {
        players.put(player.getUuid(), player);
    }

    /**
     * @param uuid Spieler-UUID als String
     * @return der Spieler, falls online
     */
    public Optional<PlayerInfo> get(String uuid) {
        return Optional.ofNullable(players.get(uuid));
    }

    /**
     * @return alle online Spieler
     */
    public Collection<PlayerInfo> all() {
        return List.copyOf(players.readAllValues());
    }

    /**
     * @param uuid Spieler-UUID als String
     * @return {@code true}, wenn der Spieler entfernt wurde
     */
    public boolean remove(String uuid) {
        return players.remove(uuid) != null;
    }

    /**
     * @return Anzahl der online Spieler
     */
    public int size() {
        return players.size();
    }
}
