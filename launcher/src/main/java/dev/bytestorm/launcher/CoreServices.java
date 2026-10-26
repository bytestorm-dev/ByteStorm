package dev.bytestorm.launcher;

import dev.bytestorm.codec.ProtobufCodec;
import dev.bytestorm.codec.TypeRegistry;
import dev.bytestorm.core.event.EventBus;
import dev.bytestorm.core.redis.BridgeCommands;
import dev.bytestorm.core.redis.CacheService;
import dev.bytestorm.core.redis.GroupCache;
import dev.bytestorm.core.redis.LogTopic;
import dev.bytestorm.core.redis.NameAllocator;
import dev.bytestorm.core.redis.PlayerStore;
import dev.bytestorm.core.redis.PortAllocator;
import dev.bytestorm.core.redis.RedisGroupCache;
import dev.bytestorm.core.redis.ServiceRegistryStore;
import dev.bytestorm.core.rpc.RpcService;
import org.redisson.api.RedissonClient;

import java.util.Objects;

/**
 * Die aus der Redis-Verbindung aufgebauten Kern-Dienste.
 *
 * <p>Alle Bestandteile teilen sich denselben {@link RedissonClient} und dieselbe
 * {@link TypeRegistry}; so können Controller, Node und Module dieselben
 * Instanzen verwenden, statt eigene Verbindungen zu öffnen.
 *
 * @param registry        registrierte Proto-Typen
 * @param codec           Codec für Nachrichten und einfache Werte
 * @param events          Event-Bus
 * @param rpc             RPC-Dienst
 * @param services        Live-Registry der Services
 * @param players         Live-State der Spieler
 * @param ports           Port-Vergabe
 * @param names           Namensvergabe
 * @param cache           kurzlebige Caches
 * @param groups          Cache der Gruppen-Definitionen
 * @param logs            Log-Streaming
 * @param bridgeCommands  Befehle an die Bridge-Plugins
 */
public record CoreServices(
        TypeRegistry registry,
        ProtobufCodec codec,
        EventBus events,
        RpcService rpc,
        ServiceRegistryStore services,
        PlayerStore players,
        PortAllocator ports,
        NameAllocator names,
        CacheService cache,
        GroupCache groups,
        LogTopic logs,
        BridgeCommands bridgeCommands) {

    /**
     * @param registry registrierte Proto-Typen
     * @param codec    Codec für Nachrichten
     * @param events   Event-Bus
     * @param rpc      RPC-Dienst
     * @param services Live-Registry
     * @param players  Spieler-State
     * @param ports    Port-Vergabe
     * @param names    Namensvergabe
     * @param cache    Caches
     * @param groups   Cache der Gruppen
     * @param logs     Log-Streaming
     */
    public CoreServices {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(rpc, "rpc");
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(ports, "ports");
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(logs, "logs");
        Objects.requireNonNull(bridgeCommands, "bridgeCommands");
    }

    /**
     * Baut alle Dienste auf einer bestehenden Verbindung auf.
     *
     * @param client   verbundener Redisson-Client
     * @param registry registrierte Proto-Typen
     * @param codec    Codec, der auch für die Verbindung benutzt wurde
     * @return die gebündelten Dienste
     */
    public static CoreServices create(RedissonClient client, TypeRegistry registry, ProtobufCodec codec) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(codec, "codec");

        return new CoreServices(
                registry,
                codec,
                new EventBus(client, registry),
                new RpcService(client),
                new ServiceRegistryStore(client),
                new PlayerStore(client),
                new PortAllocator(client),
                new NameAllocator(client),
                new CacheService(client),
                new RedisGroupCache(client),
                new LogTopic(client),
                new BridgeCommands(client));
    }
}
