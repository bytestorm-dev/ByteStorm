package dev.bytestorm.bridge.paper.placeholder;

import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaceholderValuesTest {

    private static final PlaceholderValues.Local LOCAL =
            new PlaceholderValues.Local("Lobby-1", "paper", "1.21.8", 7, 100, "Willkommen");

    private final List<ServiceInfo> registry = new ArrayList<>(List.of(
            service("Lobby-1", "Lobby", ServiceState.ONLINE, 7, 100, true, 2),
            service("Lobby-2", "Lobby", ServiceState.ONLINE, 3, 50, false, 0),
            service("Proxy-1", "Proxy", ServiceState.ONLINE, 0, 0, false, 0),
            service("Event-1", "Event", ServiceState.STOPPED, 40, 40, false, 0),
            service("Event-2", "Event", ServiceState.CRASHED, 0, 40, false, 0)));

    private final PlaceholderValues values = new PlaceholderValues(() -> registry, () -> LOCAL);

    @Test
    void localValuesDescribeThisServer() {
        assertEquals("Lobby-1", values.value("service"));
        assertEquals("paper", values.value("platform"));
        assertEquals("1.21.8", values.value("platform_version"));
        assertEquals("Willkommen", values.value("motd"));
        assertEquals("7", values.value("players_current"));
        assertEquals("100", values.value("players_max_current"));
    }

    @Test
    void networkPlayersCountOnlyOnlineServices() {
        assertEquals("10", values.value("players"), "40 Spieler eines gestoppten Services zählen nicht");
        assertEquals("150", values.value("players_max"), "ein Service ohne Grenze zählt nicht mit");
    }

    @Test
    void serviceCountsSplitRunningAndKnown() {
        assertEquals("3", values.value("services"));
        assertEquals("5", values.value("services_all"));
        assertEquals("2", values.value("groups"), "Event hat keinen laufenden Service");
        assertEquals("1", values.value("crashed"));
    }

    @Test
    void servicePlaceholdersResolveNamesRegardlessOfCase() {
        assertEquals("3", values.value("service_players_lobby-2"));
        assertEquals("Lobby", values.value("service_group_LOBBY-2"));
        assertEquals("ONLINE", values.value("service_state_lobby-1"));
        assertEquals("50", values.value("service_max_players_Lobby-2"));
        assertEquals("30000", values.value("service_port_Lobby-1"));
    }

    @Test
    void unknownNamesLeaveAnEmptyValue() {
        assertEquals("", values.value("service_players_Nope-9"));
        assertEquals("", values.value("service_state_"));
        assertEquals("", values.value("gibt_es_nicht"));
        assertEquals("", values.value(""));
        assertEquals("", values.value(null));
    }

    @Test
    void groupsSumTheirRunningServices() {
        assertEquals("10", values.value("group_players_Lobby"));
        assertEquals("2", values.value("group_services_Lobby"));
        assertEquals("0", values.value("group_players_event"), "gestoppte Services zählen nicht");
        assertEquals("0", values.value("group_services_Event"));
    }

    @Test
    void theFallbackIsTheOnlineServiceWithTheLowestPriority() {
        assertEquals("Lobby-1", values.value("group_fallback_Lobby"));

        registry.add(service("Lobby-9", "Lobby", ServiceState.ONLINE, 0, 20, true, 1));
        assertEquals("Lobby-9", values.value("group_fallback_Lobby"), "kleinere Priorität gewinnt");
        assertEquals("-", values.value("group_fallback_Proxy"), "ohne Fallback bleibt ein Strich");
    }

    @Test
    void localValuesAreReadPerRequest() {
        AtomicReference<PlaceholderValues.Local> local = new AtomicReference<>(LOCAL);
        PlaceholderValues live = new PlaceholderValues(() -> registry, local::get);

        assertEquals("7", live.value("players_current"));

        local.set(new PlaceholderValues.Local("Lobby-1", "paper", "1.21.8", 9, 100, "Neu"));
        assertEquals("9", live.value("players_current"), "der Wert folgt dem Server");
        assertEquals("Neu", live.value("motd"));
    }

    private static ServiceInfo service(String name, String group, ServiceState state,
                                       int players, int max, boolean fallback, int priority) {
        return ServiceInfo.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setName(name)
                .setGroup(group)
                .setHost("127.0.0.1")
                .setPort(30000)
                .setState(state)
                .setOnlinePlayers(players)
                .setMaxPlayers(max)
                .setFallback(fallback)
                .setFallbackPriority(priority)
                .build();
    }
}
