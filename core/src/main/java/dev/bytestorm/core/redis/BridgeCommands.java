package dev.bytestorm.core.redis;

import com.google.protobuf.Message;
import dev.bytestorm.api.bridge.BridgeChannels;
import dev.bytestorm.api.bridge.PlayerBridge;
import dev.bytestorm.proto.ConnectPlayerRequest;
import dev.bytestorm.proto.KickPlayerRequest;
import org.redisson.api.RedissonClient;

import java.util.Objects;

/**
 * Befehle der Cloud an eine Bridge (§15, §17).
 *
 * <p>Veröffentlicht auf {@code bs:bridge:<service-id>:commands}. Auf demselben
 * Kanal liegen {@code ExecuteCommand} und {@code StopServiceRequest}; jedes
 * Plugin prüft, ob die Nachricht an die eigene Service-ID geht.
 *
 * <p>Konsolenbefehle schickt der Node direkt an den Prozess (§12) — dieser Weg
 * ist für Aktionen gedacht, die das Plugin selbst umsetzen muss, etwa
 * Spieler-Aktionen eines Proxys, der keine Konsole im Sinne des Nodes ist.
 */
public final class BridgeCommands implements PlayerBridge {

    private final RedissonClient client;

    /**
     * @param client verbundener Redisson-Client
     */
    public BridgeCommands(RedissonClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Schickt einen Spieler auf einen anderen Service.
     *
     * @param serviceId Proxy, an den die Aufforderung geht
     * @param player    Spielername
     * @param target    Ziel-Service des Spielers
     */
    @Override
    public void connectPlayer(String serviceId, String player, String target) {
        publish(serviceId, ConnectPlayerRequest.newBuilder()
                .setService(serviceId)
                .setPlayer(player)
                .setTarget(target)
                .build());
    }

    /**
     * Trennt einen Spieler vom Netz.
     *
     * @param serviceId Proxy, an den die Aufforderung geht
     * @param player    Spielername
     * @param reason    Begründung für den Spieler, darf leer sein
     */
    @Override
    public void kickPlayer(String serviceId, String player, String reason) {
        publish(serviceId, KickPlayerRequest.newBuilder()
                .setService(serviceId)
                .setPlayer(player)
                .setReason(reason == null ? "" : reason)
                .build());
    }

    private void publish(String serviceId, Message message) {
        client.getTopic(BridgeChannels.commands(serviceId)).publish(message);
    }
}
