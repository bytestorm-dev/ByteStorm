package dev.bytestorm.controller.player;

import dev.bytestorm.api.bridge.PlayerBridge;
import dev.bytestorm.controller.registry.ServiceRegistry;
import dev.bytestorm.core.mongo.model.StoredPlayer;
import dev.bytestorm.proto.PlayerInfo;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceType;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Spieler-Auskunft und Spieler-Aktionen (§7, §8, §14, §17).
 *
 * <p>Der Live-Zustand kommt aus Redis ({@code bs:players}), die Historie aus
 * MongoDB. Beides wird als Funktion hereingereicht, damit die Fassade ohne
 * laufende Infrastruktur prüfbar bleibt; der Bootstrap verbindet sie mit
 * {@code PlayerStore} und {@code PlayerRepository}.
 *
 * <p>Aktionen gehen an den Proxy, auf dem der Spieler verbunden ist: nur ein
 * Proxy kann einen Spieler auf einen anderen Service schicken oder ihn vom Netz
 * trennen — ein Kick auf einem Backend würfe ihn nur auf einen anderen Server
 * (§14).
 */
@Slf4j
public final class PlayerManager {

    private final Supplier<Collection<PlayerInfo>> live;
    private final Function<String, Optional<StoredPlayer>> history;
    private final ServiceRegistry registry;
    private final PlayerBridge bridge;

    /**
     * @param live     liefert den Live-Zustand aller Spieler aus Redis
     * @param history  sucht die Historie eines Spielers in MongoDB
     * @param registry Services, um Proxy und Ziel aufzulösen
     * @param bridge   Kanal zu den Bridge-Plugins der Proxies
     */
    public PlayerManager(Supplier<Collection<PlayerInfo>> live,
                         Function<String, Optional<StoredPlayer>> history,
                         ServiceRegistry registry,
                         PlayerBridge bridge) {
        this.live = Objects.requireNonNull(live, "live");
        this.history = Objects.requireNonNull(history, "history");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    /**
     * @return alle online Spieler, alphabetisch nach Name
     */
    public List<PlayerInfo> online() {
        return live.get().stream()
                .sorted(Comparator.comparing(PlayerInfo::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Sucht einen Spieler im Live-State zuerst, dann in der Historie.
     *
     * @param name Spielername
     * @return Live-Zustand und/oder Historie, leer wenn der Name unbekannt ist
     */
    public Optional<PlayerDetail> find(String name) {
        Objects.requireNonNull(name, "name");
        Optional<PlayerInfo> connected = connectable(name);
        Optional<StoredPlayer> stored = history.apply(name);
        if (connected.isEmpty() && stored.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PlayerDetail(connected.orElse(null), stored.orElse(null)));
    }

    /**
     * Schickt einen Spieler auf einen anderen Service.
     *
     * @param player Spielername
     * @param target Ziel-Service
     * @return der Proxy, an den die Aufforderung ging
     * @throws IllegalArgumentException wenn der Spieler nicht online ist, das Ziel
     *                                  kein laufender Server ist oder der Proxy fehlt
     */
    public String connect(String player, String target) {
        PlayerInfo connected = requireOnline(player);
        ServiceInfo destination = requireServer(target);
        ServiceInfo proxy = requireProxy(connected);
        bridge.connectPlayer(proxy.getId(), connected.getName(), destination.getName());
        log.info("Spieler '{}' wird über '{}' nach '{}' geschickt",
                connected.getName(), proxy.getName(), destination.getName());
        return proxy.getName();
    }

    /**
     * Trennt einen Spieler vom Netz.
     *
     * @param player Spielername
     * @param reason Begründung für den Spieler, darf leer sein
     * @return der Proxy, an den die Aufforderung ging
     * @throws IllegalArgumentException wenn der Spieler nicht online ist oder der Proxy fehlt
     */
    public String kick(String player, String reason) {
        PlayerInfo connected = requireOnline(player);
        ServiceInfo proxy = requireProxy(connected);
        String message = reason == null ? "" : reason.trim();
        bridge.kickPlayer(proxy.getId(), connected.getName(), message);
        log.info("Spieler '{}' wird über '{}' getrennt{}", connected.getName(), proxy.getName(),
                message.isEmpty() ? "" : " (" + message + ")");
        return proxy.getName();
    }

    private PlayerInfo requireOnline(String name) {
        return connectable(name).orElseThrow(() ->
                new IllegalArgumentException("Spieler '" + name + "' ist nicht online"));
    }

    private Optional<PlayerInfo> connectable(String name) {
        Objects.requireNonNull(name, "name");
        return live.get().stream()
                .filter(player -> player.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    private ServiceInfo requireServer(String name) {
        ServiceInfo service = registry.byName(name)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannter Service: " + name));
        if (service.getType() != ServiceType.SERVER) {
            throw new IllegalArgumentException("Service '" + name + "' ist ein Proxy, kein Server");
        }
        if (service.getState() != ServiceState.ONLINE) {
            throw new IllegalArgumentException("Service '" + name + "' ist nicht ONLINE ("
                    + service.getState() + ")");
        }
        return service;
    }

    private ServiceInfo requireProxy(PlayerInfo player) {
        String proxy = player.getProxy();
        if (proxy.isBlank()) {
            throw new IllegalArgumentException("Für '" + player.getName()
                    + "' ist kein Proxy bekannt; die Bridge meldet ihn erst nach dem Login");
        }
        ServiceInfo service = registry.byName(proxy)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannter Proxy: " + proxy));
        if (service.getState() != ServiceState.ONLINE) {
            throw new IllegalArgumentException("Proxy '" + proxy + "' ist nicht ONLINE ("
                    + service.getState() + ")");
        }
        return service;
    }

    /**
     * Live-Zustand und Historie eines Spielers.
     *
     * @param live   Zustand aus Redis, {@code null} wenn nicht online
     * @param stored Historie aus MongoDB, {@code null} wenn unbekannt
     */
    public record PlayerDetail(PlayerInfo live, StoredPlayer stored) {

        /**
         * @return {@code true}, wenn der Spieler gerade verbunden ist
         */
        public boolean online() {
            return live != null;
        }
    }
}
