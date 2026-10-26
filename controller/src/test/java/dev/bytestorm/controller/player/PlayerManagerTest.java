package dev.bytestorm.controller.player;

import dev.bytestorm.api.bridge.PlayerBridge;
import dev.bytestorm.controller.player.PlayerManager.PlayerDetail;
import dev.bytestorm.controller.registry.ServiceRegistry;
import dev.bytestorm.core.mongo.model.StoredPlayer;
import dev.bytestorm.proto.PlayerInfo;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerManagerTest {

    /** Feste UUID des Spielers, damit die erwarteten Aufrufe lesbar bleiben. */
    private static final UUID BOB = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final String PROXY_ID = "aaaaaaaa-0000-0000-0000-000000000001";

    private static final String LOBBY_ID = "bbbbbbbb-0000-0000-0000-000000000002";

    private final List<PlayerInfo> online = new ArrayList<>();
    private final List<ServiceInfo> services = new ArrayList<>();
    private final List<StoredPlayer> history = new ArrayList<>();
    private final FakeBridge bridge = new FakeBridge();

    @Test
    void listsPlayersAlphabetically() {
        add(player("zoe"), player("bob"));

        List<PlayerInfo> players = manager(proxy(), lobby()).online();

        assertEquals(List.of("bob", "zoe"), players.stream().map(PlayerInfo::getName).toList());
    }

    @Test
    void findsLiveStateAndHistoryTogether() {
        add(player("bob"));
        history.add(stored());

        PlayerDetail detail = manager(proxy()).find("BOB").orElseThrow();

        assertTrue(detail.online());
        assertEquals(BOB.toString(), detail.live().getUuid());
        assertEquals("bob", detail.stored().name());
        assertEquals(Duration.ofHours(3), detail.stored().playtime());
    }

    @Test
    void findsPlayersThatAreOnlyInTheHistory() {
        history.add(stored());

        PlayerDetail detail = manager(proxy()).find("bob").orElseThrow();

        assertFalse(detail.online());
        assertEquals("bob", detail.stored().name());
        assertTrue(manager(proxy()).find("niemand").isEmpty());
    }

    @Test
    void connectSendsTheRequestToThePlayersProxy() {
        add(player("bob"));

        assertEquals("Proxy-1", manager(proxy(), lobby()).connect("bob", "Lobby-1"));
        assertEquals(List.of("connect " + PROXY_ID + " bob -> Lobby-1"), bridge.calls);
    }

    @Test
    void kickSendsTheReasonToThePlayersProxy() {
        add(player("bob"));

        assertEquals("Proxy-1", manager(proxy()).kick("bob", "  zu viele Kicks  "));
        assertEquals(List.of("kick " + PROXY_ID + " bob (zu viele Kicks)"), bridge.calls);
    }

    @Test
    void kickWithoutReasonStillSendsTheRequest() {
        add(player("bob"));

        manager(proxy()).kick("bob", null);

        assertEquals(List.of("kick " + PROXY_ID + " bob"), bridge.calls);
    }

    @Test
    void refusesConnectAndKickForPlayersThatAreNotOnline() {
        PlayerManager manager = manager(proxy(), lobby());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> manager.connect("bob", "Lobby-1"));
        assertTrue(error.getMessage().contains("nicht online"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> manager.kick("bob", ""));
        assertTrue(bridge.calls.isEmpty());
    }

    @Test
    void refusesTargetsThatAreNoUnknownOrNotRunningServers() {
        add(player("bob"));
        PlayerManager manager = manager(proxy(), lobby());

        assertTrue(assertThrows(IllegalArgumentException.class, () -> manager.connect("bob", "Lobby-9"))
                .getMessage().contains("Unbekannter Service"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> manager.connect("bob", "Proxy-1"))
                .getMessage().contains("kein Server"));
        assertTrue(bridge.calls.isEmpty());
    }

    @Test
    void refusesTargetsThatAreNotOnlineYet() {
        add(player("bob"));
        services.add(service(LOBBY_ID, "Lobby-1", "Lobby", ServiceType.SERVER, ServiceState.STARTING));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> manager(proxy()).connect("bob", "Lobby-1"));
        assertTrue(error.getMessage().contains("nicht ONLINE"), error.getMessage());
    }

    @Test
    void needsAProxyForPlayerActions() {
        add(PlayerInfo.newBuilder().setUuid(BOB.toString()).setName("bob").build());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> manager().kick("bob", ""));
        assertTrue(error.getMessage().contains("kein Proxy bekannt"), error.getMessage());
    }

    private PlayerManager manager(ServiceInfo... known) {
        services.addAll(List.of(known));
        Collection<ServiceInfo> live = List.copyOf(services);
        ServiceRegistry registry = new ServiceRegistry(() -> live, List::of);
        return new PlayerManager(() -> online,
                name -> history.stream()
                        .filter(stored -> stored.name().equalsIgnoreCase(name))
                        .findFirst(),
                registry, bridge);
    }

    private void add(PlayerInfo... players) {
        online.addAll(List.of(players));
    }

    private static PlayerInfo player(String name) {
        return PlayerInfo.newBuilder()
                .setUuid(BOB.toString())
                .setName(name)
                .setProxy("Proxy-1")
                .setServer("Lobby-1")
                .build();
    }

    private static ServiceInfo proxy() {
        return service(PROXY_ID, "Proxy-1", "Proxy", ServiceType.PROXY, ServiceState.ONLINE);
    }

    private static ServiceInfo lobby() {
        return service(LOBBY_ID, "Lobby-1", "Lobby", ServiceType.SERVER, ServiceState.ONLINE);
    }

    private static ServiceInfo service(String id, String name, String group,
                                       ServiceType type, ServiceState state) {
        return ServiceInfo.newBuilder()
                .setId(id)
                .setName(name)
                .setGroup(group)
                .setType(type)
                .setState(state)
                .setPort(type == ServiceType.PROXY ? 25565 : 30001)
                .build();
    }

    private static StoredPlayer stored() {
        return new StoredPlayer(BOB, "bob", Duration.ofHours(3),
                Instant.parse("2026-10-20T18:00:00Z"), Instant.parse("2026-10-26T17:30:00Z"), "Lobby-1");
    }

    private static final class FakeBridge implements PlayerBridge {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void connectPlayer(String serviceId, String player, String target) {
            calls.add("connect " + serviceId + " " + player + " -> " + target);
        }

        @Override
        public void kickPlayer(String serviceId, String player, String reason) {
            calls.add("kick " + serviceId + " " + player
                    + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")"));
        }
    }
}
