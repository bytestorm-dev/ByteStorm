package dev.bytestorm.core.config;

import dev.bytestorm.api.service.PortRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortSettingsTest {

    @Test
    void proxyRangeEndsBeforeTheServerPool() {
        PortRange range = new PortSettings(25565, 30000).proxyRange();

        assertEquals(25565, range.start());
        assertEquals(29999, range.end());
        assertTrue(range.contains(25566));
        assertFalse(range.contains(30000));
    }

    @Test
    void proxyRangeKeepsTheSinglePortWhenTheServerPoolStartsEarlier() {
        PortRange range = new PortSettings(25565, 25565).proxyRange();

        assertEquals(25565, range.start());
        assertEquals(25565, range.end());
    }

    @Test
    void proxyRangeNeverRunsPastTheLastPort() {
        PortRange range = new PortSettings(65000, 65535).proxyRange();

        assertEquals(65000, range.start());
        assertEquals(65534, range.end());
    }
}
