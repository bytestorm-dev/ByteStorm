package dev.bytestorm.bridge.common;

import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceStateChanged;

import java.util.Collection;

/**
 * Plattformspezifischer Teil einer Bridge (§15).
 *
 * <p>Die Implementierung lebt im jeweiligen Plugin (Paper, Velocity, Bungee).
 * {@link CloudBridge} ruft sie aus Redisson-Threads auf; die Implementierung
 * muss selbst dafür sorgen, dass Server-API-Aufrufe auf dem richtigen Thread
 * laufen. Spielerzahlen und MOTD sollten zwischengespeichert werden, damit ein
 * Heartbeat nicht auf den Server-Thread warten muss.
 */
public interface BridgePlatform {

    /**
     * @return Plattformname, z. B. {@code paper} oder {@code velocity}
     */
    String platformName();

    /**
     * @return Plattformversion, z. B. {@code 1.21.8}
     */
    String platformVersion();

    /**
     * @return aktuelle Spielerzahl
     */
    int onlinePlayers();

    /**
     * @return maximale Spielerzahl
     */
    int maxPlayers();

    /**
     * @return MOTD oder ein leerer String
     */
    String motd();

    /**
     * Führt einen Konsolenbefehl aus.
     *
     * @param command Befehl ohne Zeilenende
     */
    void executeCommand(String command);

    /**
     * Fährt den Service herunter, z. B. nach {@code StopServiceRequest}.
     */
    void shutdown();

    /**
     * Schickt einen Spieler auf einen anderen Service (§14, §17).
     *
     * <p>Umgesetzt von Proxies: nur sie kennen den Spieler. Backend-Services
     * lassen die Voreinstellung stehen, die den Aufruf ablehnt; die Cloud
     * meldet die Ablehnung dann als Warnung im Log.
     *
     * @param player Spielername
     * @param target Name des Ziel-Services
     */
    default void connectPlayer(String player, String target) {
        throw new UnsupportedOperationException(
                platformName() + " kann Spieler nicht auf andere Services schicken");
    }

    /**
     * Trennt einen Spieler vom Netz (§14, §17).
     *
     * <p>Nur der Proxy, auf dem der Spieler verbunden ist, kann ihn trennen — ein
     * Kick auf einem Backend würfe den Spieler nur auf einen anderen Server
     * ({@code KickedFromServerEvent}).
     *
     * @param player Spielername
     * @param reason Begründung für den Spieler
     */
    default void kickPlayer(String player, String reason) {
        throw new UnsupportedOperationException(platformName() + " kann Spieler nicht trennen");
    }

    /**
     * Reagiert auf den Zustandswechsel eines Services. Ein Proxy registriert
     * daraus seine Server, ein Service kann etwa Spieler kicken, wenn die Cloud
     * ihn stoppt.
     *
     * @param event Zustandswechsel
     */
    default void onStateChanged(ServiceStateChanged event) {
    }

    /**
     * Liefert die Services, die beim Verbindungsaufbau bereits in der Registry
     * standen. Ohne das würden Proxies nur Starts nach ihrem eigenen Handshake
     * sehen.
     *
     * <p>Die Voreinstellung behandelt jeden Eintrag wie einen Zustandswechsel
     * ohne Übergang; Implementierungen mit eigenem Snapshot-Verhalten können
     * das überschreiben.
     *
     * @param services aktueller Registry-Inhalt
     */
    default void onSnapshot(Collection<ServiceInfo> services) {
        for (ServiceInfo service : services) {
            onStateChanged(ServiceStateChanged.newBuilder()
                    .setService(service)
                    .setPrevious(service.getState())
                    .build());
        }
    }
}
