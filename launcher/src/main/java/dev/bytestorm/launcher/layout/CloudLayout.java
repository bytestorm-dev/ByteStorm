package dev.bytestorm.launcher.layout;

import dev.bytestorm.node.template.TemplateLayers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Verzeichnislayout der Cloud (§5.1).
 *
 * <p>Legt die Struktur unterhalb des Cloud-Verzeichnisses an und macht die
 * Pfade an einer Stelle verfügbar. {@link #create()} ist idempotent: vorhandene
 * Verzeichnisse bleiben unangetastet, fehlende werden ergänzt.
 */
public final class CloudLayout {

    /** Alle Verzeichnisse, die eine Cloud benötigt. */
    public static final List<String> DIRECTORIES = List.of(
            "lib",
            "config",
            "config/groups",
            "config/modules",
            "templates",
            "templates/" + TemplateLayers.ALL,
            "templates/" + TemplateLayers.PROXY,
            "templates/" + TemplateLayers.SERVER,
            "templates/" + TemplateLayers.FALLBACK,
            "platforms",
            "running",
            "static",
            "crash-reports",
            "logs");

    private final Path root;

    /**
     * @param root Cloud-Verzeichnis; relative Pfade werden gegen das
     *             Arbeitsverzeichnis aufgelöst
     */
    public CloudLayout(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /**
     * Legt alle Verzeichnisse an.
     *
     * @return das Cloud-Verzeichnis
     * @throws IOException wenn ein Verzeichnis nicht angelegt werden kann
     */
    public Path create() throws IOException {
        for (String directory : DIRECTORIES) {
            Files.createDirectories(root.resolve(directory));
        }
        return root;
    }

    /**
     * @return das Cloud-Verzeichnis
     */
    public Path root() {
        return root;
    }

    /**
     * @return Ablage für externe Jars
     */
    public Path lib() {
        return root.resolve("lib");
    }

    /**
     * @return Konfigurationsverzeichnis
     */
    public Path config() {
        return root.resolve("config");
    }

    /**
     * @return zusätzliche Gruppen-Konfigurationen (§4)
     */
    public Path groups() {
        return config().resolve("groups");
    }

    /**
     * @return Konfiguration der optionalen Module
     */
    public Path modules() {
        return config().resolve("modules");
    }

    /**
     * @return geteilte Template-Ordner (§10)
     */
    public Path templates() {
        return root.resolve("templates");
    }

    /**
     * @return heruntergeladene Plattform-Jars (§13)
     */
    public Path platforms() {
        return root.resolve("platforms");
    }

    /**
     * @return Arbeitsverzeichnisse dynamischer Services
     */
    public Path running() {
        return root.resolve("running");
    }

    /**
     * @return persistente Service-Ordner
     */
    public Path staticServices() {
        return root.resolve("static");
    }

    /**
     * @return Crash-Berichte (§12.5)
     */
    public Path crashReports() {
        return root.resolve("crash-reports");
    }

    /**
     * @return Log-Verzeichnis
     */
    public Path logs() {
        return root.resolve("logs");
    }

    /**
     * Ablage für Sicherungen. Entsteht erst mit der ersten Sicherung: Wer nie
     * sichert, soll kein leeres Verzeichnis im Layout haben.
     *
     * @return Zielordner für {@code backup}
     */
    public Path backups() {
        return root.resolve("backups");
    }
}
