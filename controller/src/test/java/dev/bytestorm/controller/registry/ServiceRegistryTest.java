package dev.bytestorm.controller.registry;

import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceRegistryTest {

    private static final String LOBBY_ID = UUID.randomUUID().toString();
    private static final String PROXY_ID = UUID.randomUUID().toString();

    @Test
    void servicesAreSortedByName() {
        ServiceRegistry registry = new ServiceRegistry(
                () -> List.of(info(LOBBY_ID, "Lobby-2", "Lobby", ServiceState.ONLINE),
                        info(PROXY_ID, "Proxy-1", "Proxy", ServiceState.ONLINE)),
                List::of);

        assertEquals(List.of("Lobby-2", "Proxy-1"),
                registry.all().stream().map(ServiceInfo::getName).toList());
    }

    @Test
    void liveEntriesWinOverTheLifecycleView() {
        ServiceRegistry registry = new ServiceRegistry(
                () -> List.of(info(LOBBY_ID, "Lobby-1", "Lobby", ServiceState.ONLINE).toBuilder()
                        .setOnlinePlayers(7)
                        .setMaxPlayers(100)
                        .build()),
                () -> List.of(info(LOBBY_ID, "Lobby-1", "Lobby", ServiceState.STARTING).toBuilder()
                        .setOnlinePlayers(0)
                        .build()));

        ServiceInfo info = registry.all().getFirst();

        assertEquals(ServiceState.ONLINE, info.getState(), "Redis ist die Quelle der Wahrheit");
        assertEquals(7, info.getOnlinePlayers());
    }

    @Test
    void servicesWithoutLiveEntryStayVisible() {
        ServiceRegistry registry = new ServiceRegistry(
                () -> List.of(info(PROXY_ID, "Proxy-1", "Proxy", ServiceState.ONLINE)),
                () -> List.of(info(LOBBY_ID, "Lobby-1", "Lobby", ServiceState.PREPARING)));

        assertEquals(List.of("Lobby-1", "Proxy-1"),
                registry.all().stream().map(ServiceInfo::getName).toList());
        assertEquals(ServiceState.PREPARING, registry.byName("Lobby-1").orElseThrow().getState());
    }

    @Test
    void lookupsUseGroupNameAndId() {
        ServiceRegistry registry = registry();

        assertEquals(1, registry.byGroup("Lobby").size());
        assertEquals("Lobby-1", registry.byGroup("Lobby").getFirst().getName());
        assertTrue(registry.byGroup("Survival").isEmpty());
        assertTrue(registry.byName("Proxy-1").isPresent());
        assertTrue(registry.byName("Proxy-9").isEmpty());
        assertTrue(registry.byId(LOBBY_ID).isPresent());
        assertTrue(registry.byId(UUID.randomUUID().toString()).isEmpty());
        assertTrue(registry.contains("Lobby-1"));
        assertFalse(registry.contains("Lobby-2"));
    }

    @Test
    void onlineFiltersStateAndCountsPlayers() {
        ServiceRegistry registry = new ServiceRegistry(
                () -> List.of(info(LOBBY_ID, "Lobby-1", "Lobby", ServiceState.ONLINE).toBuilder()
                                .setOnlinePlayers(5)
                                .build(),
                        info(PROXY_ID, "Proxy-1", "Proxy", ServiceState.ONLINE)),
                () -> List.of(info(UUID.randomUUID().toString(), "Lobby-2", "Lobby", ServiceState.STARTING)
                        .toBuilder().setOnlinePlayers(9).build()));

        assertEquals(List.of("Lobby-1", "Proxy-1"),
                registry.online().stream().map(ServiceInfo::getName).toList());
        assertEquals(5, registry.onlinePlayers(), "nur ONLINE-Services zählen Spieler");
    }

    @Test
    void emptyRegistriesYieldAnEmptyView() {
        ServiceRegistry registry = new ServiceRegistry(List::of, List::of);

        assertTrue(registry.all().isEmpty());
        assertTrue(registry.online().isEmpty());
        assertEquals(0, registry.onlinePlayers());
    }

    private static ServiceRegistry registry() {
        return new ServiceRegistry(
                () -> List.of(info(PROXY_ID, "Proxy-1", "Proxy", ServiceState.ONLINE)),
                () -> List.of(info(LOBBY_ID, "Lobby-1", "Lobby", ServiceState.ONLINE)));
    }

    private static ServiceInfo info(String id, String name, String group, ServiceState state) {
        return ServiceInfo.newBuilder()
                .setId(id)
                .setName(name)
                .setGroup(group)
                .setState(state)
                .setType(ServiceType.SERVER)
                .build();
    }
}
