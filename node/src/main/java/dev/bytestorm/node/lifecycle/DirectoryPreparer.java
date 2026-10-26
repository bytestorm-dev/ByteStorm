package dev.bytestorm.node.lifecycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Default-Vorbereitung ohne Templates: legt den Service-Ordner an und löscht ihn
 * für dynamische Services nach dem Stop (§9.3, §9.4).
 *
 * <p>Statische Services behalten ihren Ordner. {@code reapply} ist hier ein
 * No-op; die Template-Logik aus Phase 5 ersetzt diese Implementierung.
 */
public final class DirectoryPreparer implements ServicePreparer {

    @Override
    public void prepare(ServiceDefinition definition) throws IOException {
        Files.createDirectories(definition.directory());
    }

    @Override
    public void cleanup(ServiceDefinition definition) throws IOException {
        if (!definition.dynamic() || !Files.exists(definition.directory())) {
            return;
        }
        Path directory = definition.directory();
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
