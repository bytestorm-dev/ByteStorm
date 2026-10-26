package dev.bytestorm.api.bridge;

/**
 * Aufforderungen an die Bridge eines Proxys (§14, §15, §17).
 *
 * <p>Nur ein Proxy kann einen Spieler auf einen anderen Service schicken oder ihn
 * vom Netz trennen — ein Backend wirft ihn höchstens auf einen anderen Server.
 * Die Cloud veröffentlicht dafür Nachrichten auf
 * {@code bs:bridge:<service-id>:commands} ({@link BridgeChannels#commands(String)});
 * umgesetzt wird das von {@code CloudBridge} und den Plattform-Plugins.
 */
public interface PlayerBridge {

    /**
     * Schickt einen Spieler auf einen anderen Service.
     *
     * @param serviceId Service-UUID des Proxys als String
     * @param player    Spielername
     * @param target    Name des Ziel-Services
     */
    void connectPlayer(String serviceId, String player, String target);

    /**
     * Trennt einen Spieler vom Netz.
     *
     * @param serviceId Service-UUID des Proxys als String
     * @param player    Spielername
     * @param reason    Begründung für den Spieler, darf leer sein
     */
    void kickPlayer(String serviceId, String player, String reason);
}
