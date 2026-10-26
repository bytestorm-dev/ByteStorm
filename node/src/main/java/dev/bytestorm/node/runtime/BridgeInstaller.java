package dev.bytestorm.node.runtime;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Stellt die Bridge-Jar im {@code plugins/}-Ordner eines Services bereit
 * (Konzept §12.2).
 *
 * <p>Eine identische Datei bleibt unangetastet; abweichende werden über eine
 * temporäre Datei atomar ersetzt, damit ein Absturz keine halbe Jar hinterlässt.
 */
final class BridgeInstaller {

    private BridgeInstaller() {
    }

    /**
     * @param serviceDirectory Service-Ordner
     * @param bridgeJar        Bridge-Jar oder {@code null}, dann passiert nichts
     * @throws IOException wenn die Jar fehlt oder nicht kopiert werden kann
     */
    static void install(Path serviceDirectory, Path bridgeJar) throws IOException {
        if (bridgeJar == null) {
            return;
        }
        if (!Files.isRegularFile(bridgeJar)) {
            throw new IOException("Bridge-Jar fehlt: " + bridgeJar);
        }

        Path plugins = Files.createDirectories(serviceDirectory.resolve("plugins"));
        Path target = plugins.resolve(bridgeJar.getFileName().toString());
        if (Files.isRegularFile(target) && Files.mismatch(bridgeJar, target) == -1L) {
            return;
        }

        Path temp = Files.createTempFile(plugins, ".bridge-", ".tmp");
        try {
            Files.copy(bridgeJar, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
