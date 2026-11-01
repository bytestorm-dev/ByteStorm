package dev.bytestorm.bridge.common;

import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallbackSelectorTest {

    @Test
    void lowerPriorityWins() {
        ServiceInfo second = server("Lobby-2", 2);
        ServiceInfo first = server("Lobby-1", 1);

        assertEquals(first.getName(), FallbackSelector.select(List.of(second, first)).orElseThrow().getName());
    }

    @Test
    void nameBreaksPriorityTies() {
        ServiceInfo b = server("Lobby-B", 1);
        ServiceInfo a = server("Lobby-A", 1);

        assertEquals("Lobby-A", FallbackSelector.select(List.of(b, a)).orElseThrow().getName());
    }

    @Test
    void ignoresNonFallbackProxiesOfflineAndUnboundPorts() {
        ServiceInfo nonFallback = server("Lobby-1", 1).toBuilder().setFallback(false).build();
        ServiceInfo proxy = server("Proxy-1", 1).toBuilder().setType(ServiceType.PROXY).build();
        ServiceInfo offline = server("Lobby-2", 1).toBuilder().setState(ServiceState.STARTING).build();
        ServiceInfo unbound = server("Lobby-3", 1).toBuilder().setPort(0).build();

        assertTrue(FallbackSelector.select(List.of(nonFallback, proxy, offline, unbound)).isEmpty());
    }

    @Test
    void emptyDirectoryHasNoFallback() {
        assertTrue(FallbackSelector.select(List.of()).isEmpty());
    }

    @Test
    void initialServerPrefersTheFallbackTarget() {
        ServiceInfo fallback = server("Lobby-1", 1).toBuilder().setOnlinePlayers(20).build();
        ServiceInfo busyEventServer = server("Event-1", 1).toBuilder()
                .setFallback(false)
                .setOnlinePlayers(0)
                .build();

        assertEquals("Lobby-1", FallbackSelector.selectInitial(List.of(busyEventServer, fallback))
                .orElseThrow().getName());
    }

    @Test
    void initialServerFallsBackToALeastLoadedServerWithoutFallbackFlag() {
        ServiceInfo full = server("Lobby-1", 1).toBuilder()
                .setFallback(false).setOnlinePlayers(30).build();
        ServiceInfo empty = server("Lobby-2", 1).toBuilder()
                .setFallback(false).setOnlinePlayers(2).build();

        assertEquals("Lobby-2", FallbackSelector.selectInitial(List.of(full, empty))
                .orElseThrow().getName());
    }

    @Test
    void initialServerIgnoresProxiesOfflineServersAndUnboundPorts() {
        ServiceInfo proxy = server("Proxy-1", 1).toBuilder()
                .setFallback(false).setType(ServiceType.PROXY).build();
        ServiceInfo offline = server("Lobby-2", 1).toBuilder()
                .setFallback(false).setState(ServiceState.STARTING).build();
        ServiceInfo unbound = server("Lobby-3", 1).toBuilder()
                .setFallback(false).setPort(0).build();

        assertTrue(FallbackSelector.selectInitial(List.of(proxy, offline, unbound)).isEmpty());
    }

    @Test
    void exclusionSkipsTheKickedServer() {
        ServiceInfo kicked = server("Lobby-1", 1);
        ServiceInfo next = server("Lobby-2", 2);

        assertEquals("Lobby-2", FallbackSelector.select(List.of(kicked, next), "Lobby-1")
                .orElseThrow().getName());
    }

    private static ServiceInfo server(String name, int priority) {
        return ServiceInfo.newBuilder()
                .setId(name)
                .setName(name)
                .setGroup("Lobby")
                .setType(ServiceType.SERVER)
                .setState(ServiceState.ONLINE)
                .setFallback(true)
                .setFallbackPriority(priority)
                .setHost("localhost")
                .setPort(30001)
                .build();
    }
}
