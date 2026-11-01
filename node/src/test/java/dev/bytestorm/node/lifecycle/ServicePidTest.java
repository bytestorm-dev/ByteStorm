package dev.bytestorm.node.lifecycle;

import dev.bytestorm.proto.RuntimeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServicePidTest {

    @TempDir
    Path temp;

    @Test
    void writesAndReadsTheEntry() throws Exception {
        ServicePid entry = entry(4242L);

        entry.write(temp);

        assertTrue(Files.isRegularFile(ServicePid.fileIn(temp)));
        assertEquals(entry, ServicePid.read(temp).orElseThrow());
    }

    @Test
    void aMissingEntryIsEmpty() {
        assertTrue(ServicePid.read(temp).isEmpty());
    }

    @Test
    void anUnreadableEntryIsIgnored() throws Exception {
        Files.writeString(ServicePid.fileIn(temp), "kein-schluessel=wert\n");

        assertTrue(ServicePid.read(temp).isEmpty(),
                "aus einem kaputten Eintrag darf kein Prozess übernommen werden");
    }

    @Test
    void deleteRemovesTheFile() throws Exception {
        entry(4242L).write(temp);

        assertTrue(ServicePid.delete(temp));
        assertFalse(Files.exists(ServicePid.fileIn(temp)));
        assertFalse(ServicePid.delete(temp), "zweites Löschen ist wirkungslos");
    }

    @Test
    void aliveFollowsTheProcessHandle() {
        ServicePid running = entry(ProcessHandle.current().pid());
        ServicePid impossible = entry(Long.MAX_VALUE);

        assertTrue(running.alive());
        assertFalse(impossible.alive(), "eine PID, die es nicht gibt, gilt als beendet");
    }

    private static ServicePid entry(long pid) {
        return new ServicePid("7dcd75e9-d1db-4dad-86e4-35a82839728c", "Lobby-1", "Lobby", 30000, pid,
                RuntimeType.PROCESS, 1_700_000_000_000L, "/home/cloud/platforms/paper.jar", "");
    }
}
