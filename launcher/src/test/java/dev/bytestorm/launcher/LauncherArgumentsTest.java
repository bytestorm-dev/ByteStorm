package dev.bytestorm.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherArgumentsTest {

    @TempDir
    Path temp;

    @Test
    void readsTheDirectoryOption() {
        Path expected = temp.toAbsolutePath().normalize();

        assertEquals(expected, LauncherArguments.cloudDirectory(new String[]{"--dir", temp.toString()}));
        assertEquals(expected, LauncherArguments.cloudDirectory(new String[]{"-d", temp.toString()}));
    }

    @Test
    void defaultsToTheWorkingDirectory() {
        Path working = Path.of(".").toAbsolutePath().normalize();

        assertEquals(working, LauncherArguments.cloudDirectory(new String[]{}));
        assertEquals(working, LauncherArguments.cloudDirectory(new String[]{"--dir"}));
        assertEquals(working, LauncherArguments.cloudDirectory(new String[]{"irgendwas"}));
    }

    @Test
    void detectsHelpRequests() {
        assertTrue(LauncherArguments.wantsHelp(new String[]{"--help"}));
        assertTrue(LauncherArguments.wantsHelp(new String[]{"-h"}));
        assertFalse(LauncherArguments.wantsHelp(new String[]{}));
        assertFalse(LauncherArguments.wantsHelp(new String[]{"--help", "--dir", temp.toString()}));
    }
}
