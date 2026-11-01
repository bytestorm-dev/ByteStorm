package dev.bytestorm.core.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudVersionTest {

    @Test
    void comparesNumericSegments() {
        assertTrue(CloudVersion.isNewer("1.2", "1.1"));
        assertTrue(CloudVersion.isNewer("1.10", "1.9"), "10 ist größer als 9");
        assertTrue(CloudVersion.isNewer("2.0.1", "2.0"));
        assertFalse(CloudVersion.isNewer("1.1", "1.1"));
        assertFalse(CloudVersion.isNewer("1.0", "1.1"));
        assertEquals(0, CloudVersion.compare("v1.1", "1.1"));
        assertEquals(0, CloudVersion.compare("1.0", "1.0.0"));
    }

    @Test
    void developmentBuildsDoNotCountAsOutdated() {
        assertFalse(CloudVersion.isNewer("1.1", "2.0-SNAPSHOT"));
    }

    @Test
    void prereleasesSortBeforeTheirRelease() {
        assertTrue(CloudVersion.isNewer("1.0", "1.0-SNAPSHOT"));
        assertTrue(CloudVersion.isNewer("1.0-BETA", "1.0-SNAPSHOT"));
        assertTrue(CloudVersion.isNewer("1.0-RC", "1.0-BETA"));
        assertFalse(CloudVersion.isNewer("1.0-SNAPSHOT", "1.0"));
        assertTrue(CloudVersion.isNewer("1.0-SNAPSHOT", "0.9"));
    }
}
