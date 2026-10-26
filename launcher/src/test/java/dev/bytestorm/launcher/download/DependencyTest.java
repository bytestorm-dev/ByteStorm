package dev.bytestorm.launcher.download;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DependencyTest {

    private static final String SHA = "a".repeat(64);

    @Test
    void buildsFileNameAndRepositoryPath() {
        Dependency dependency = new Dependency("eu.koboo", "en2do", "3.3.3", SHA);

        assertEquals("en2do-3.3.3.jar", dependency.fileName());
        assertEquals("eu/koboo/en2do/3.3.3/en2do-3.3.3.jar", dependency.mavenPath());
        assertEquals("eu.koboo:en2do:3.3.3", dependency.coordinates());
    }

    @Test
    void normalisesTheChecksum() {
        assertEquals(SHA, new Dependency("g", "a", "1", "  " + SHA.toUpperCase() + " ").sha256());
    }

    @Test
    void rejectsMissingOrMalformedChecksums() {
        assertThrows(IllegalArgumentException.class, () -> new Dependency("g", "a", "1", "abc"));
        assertThrows(IllegalArgumentException.class, () -> new Dependency("g", "a", "1", "z".repeat(64)));
        assertThrows(NullPointerException.class, () -> new Dependency("g", "a", "1", null));
    }

    @Test
    void rejectsBlankOrPathLikeCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new Dependency(" ", "a", "1", SHA));
        assertThrows(IllegalArgumentException.class, () -> new Dependency("g", "a/b", "1", SHA));
        assertThrows(IllegalArgumentException.class, () -> new Dependency("g", "a", "../1", SHA));
        assertThrows(IllegalArgumentException.class, () -> new Dependency("g", "a", "", SHA));
    }
}
