package dev.bytestorm.launcher.wizard;

import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultGroupsTest {

    @Test
    void definesAStaticProxyAndADynamicLobby() {
        List<ServiceGroup> groups = DefaultGroups.definitions();

        assertEquals(List.of(DefaultGroups.PROXY, DefaultGroups.LOBBY),
                groups.stream().map(ServiceGroup::getName).toList());

        ServiceGroup proxy = groups.get(0);
        assertEquals(ServiceType.PROXY, proxy.getType());
        assertEquals(ServiceMode.STATIC, proxy.getMode());
        assertEquals("velocity", proxy.getPlatform());
        assertEquals(1, proxy.getMaxServices());

        ServiceGroup lobby = groups.get(1);
        assertEquals(ServiceType.SERVER, lobby.getType());
        assertEquals(ServiceMode.DYNAMIC, lobby.getMode());
        assertEquals("paper", lobby.getPlatform());
        assertTrue(lobby.getFallback());
        assertEquals(1, lobby.getFallbackPriority());
        assertTrue(lobby.getTemplatesList().isEmpty());
        assertTrue(lobby.getMinServices() <= lobby.getMaxServices());
    }
}
