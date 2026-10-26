package dev.bytestorm.node.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortLeaseTest {

    @Test
    void releasesExactlyOnce() {
        List<Integer> released = new ArrayList<>();
        PortLease lease = PortLease.of(30001, released::add);

        assertFalse(lease.released());
        lease.close();
        lease.close();

        assertTrue(lease.released());
        assertEquals(List.of(30001), released);
    }

    @Test
    void rejectsInvalidPorts() {
        assertThrows(IllegalArgumentException.class, () -> PortLease.of(0, port -> {
        }));
        assertThrows(IllegalArgumentException.class, () -> PortLease.of(65536, port -> {
        }));
    }

    @Test
    void aFailingReleaseDoesNotEscape() {
        PortLease lease = PortLease.of(30001, port -> {
            throw new IllegalStateException("Redis weg");
        });

        lease.close();

        assertTrue(lease.released());
    }

    @Test
    void fixedLeaseHasNoCallbackButStillTracksRelease() {
        PortLease lease = PortLease.fixed(25565);

        lease.close();

        assertEquals(25565, lease.port());
        assertTrue(lease.released());
    }
}
