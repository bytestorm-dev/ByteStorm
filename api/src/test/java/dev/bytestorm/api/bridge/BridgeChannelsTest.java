package dev.bytestorm.api.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BridgeChannelsTest {

    private static final String ID = "6f1d1b4e-4f0e-4a9e-9f1a-2c3d4e5f6a7b";

    @Test
    void buildsServiceSpecificChannels() {
        assertEquals("bs:bridge:" + ID + ":ack", BridgeChannels.ack(ID));
        assertEquals("bs:bridge:" + ID + ":commands", BridgeChannels.commands(ID));
    }

    @Test
    void buildsEventChannel() {
        assertEquals("bs:event:bytestorm.ServiceStateChanged",
                BridgeChannels.event("bytestorm.ServiceStateChanged"));
    }

    @Test
    void rejectsInvalidServiceIds() {
        assertThrows(IllegalArgumentException.class, () -> BridgeChannels.ack(" "));
        assertThrows(IllegalArgumentException.class, () -> BridgeChannels.commands("a:b"));
    }
}
