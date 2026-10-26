package dev.bytestorm.launcher.layout;

import dev.bytestorm.api.service.ServiceId;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Liest die Namen der Service-Ordner, die schon auf der Platte liegen (§5.1, §15).
 *
 * <p>{@code static/<Name>} sind persistente Services, {@code running/<Name>-<UUID>}
 * die Arbeitsordner dynamischer Services. Der Abgleich beim Start nutzt diese
 * Namen, um Namenszähler anzupassen: Ein statischer Service überlebt einen
 * Neustart, sein Name darf nicht erneut vergeben werden.
 */
@UtilityClass
public final class ServiceDirectories {

    /**
     * @param layout Cloud-Layout
     * @return die Ordnernamen aus {@code static/} und {@code running/}, alphabetisch
     * @throws IOException wenn ein Verzeichnis nicht gelesen werden kann
     */
    public static Set<String> names(CloudLayout layout) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        collect(layout.staticServices(), names);
        collect(layout.running(), names);
        return names.stream().sorted().collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    /**
     * Sucht den Ordner eines Services anhand seines Namens (§10, §17).
     *
     * <p>Statische Services liegen unter {@code static/<Name>}, dynamische unter
     * {@code running/<Name>-<Service-ID>}. Die Service-ID grenzt dabei ab: Der
     * kürzere Name {@code Lobby-1} findet nicht den Ordner von {@code Lobby-10}.
     *
     * @param layout Cloud-Layout
     * @param name   Service-Name
     * @return der Ordner, falls er auf der Platte liegt
     */
    public static Optional<Path> directory(CloudLayout layout, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Path fixed = layout.staticServices().resolve(name);
        if (Files.isDirectory(fixed)) {
            return Optional.of(fixed);
        }
        String prefix = name + "-";
        if (!Files.isDirectory(layout.running())) {
            return Optional.empty();
        }
        try (Stream<Path> entries = Files.list(layout.running())) {
            return entries.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> isServiceId(path.getFileName().toString().substring(prefix.length())))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static boolean isServiceId(String value) {
        try {
            ServiceId.parse(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void collect(Path directory, Set<String> names) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            entries.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .forEach(names::add);
        }
    }
}
