package dev.bytestorm.launcher.update;

import dev.bytestorm.core.update.Updater;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateNotifierTest {

    @Test
    void namesTheCommandAndTheReleasePage() {
        Updater.Release release = release(true);

        String message = UpdateNotifier.message("1.1", release);

        assertTrue(message.contains("Update verfügbar: 1.1 → v1.2"), message);
        assertTrue(message.contains("updater update"), message);
        assertTrue(message.contains("https://example.test/v1.2"), message);
    }

    @Test
    void warnsWhenTheReleaseHasNoArchive() {
        String message = UpdateNotifier.message("1.1", release(false));

        assertTrue(message.contains("ohne Zip-Artefakt"), message);
        assertFalse(message.contains("updater update"), message);
    }

    private static Updater.Release release(boolean installable) {
        return new Updater.Release("v1.2", "1.2", "ByteStorm 1.2", false,
                "https://example.test/v1.2", Instant.parse("2026-11-01T00:00:00Z"),
                installable ? URI.create("https://example.test/asset.zip") : null,
                installable ? "bytestorm-1.2.zip" : "");
    }
}
