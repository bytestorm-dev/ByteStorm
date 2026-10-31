package dev.bytestorm.launcher.download;

import dev.bytestorm.launcher.layout.CloudLayout;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Lädt fehlende Abhängigkeiten nach {@code lib/} und pflegt {@code lib/.manifest.json} (§5.1).
 *
 * <p>Die Arbeit übernimmt {@link LibDownloader}; diese Klasse ergänzt das
 * Manifest, das den Ist-Zustand für spätere Updates festhält. Sie läuft erst,
 * wenn {@code lib/} bereits im Classpath liegt — deshalb darf sie Jackson und
 * SLF4J verwenden, {@link LibDownloader} dagegen nicht.
 *
 * <p>Schlägt eine Abhängigkeit fehl, wird das Manifest nicht geschrieben: Ein
 * halber Stand wäre schlimmer als gar keiner.
 */
@Slf4j
public final class DependencyDownloader {

    /** Basis-URL von Maven Central. */
    public static final URI MAVEN_CENTRAL = LibDownloader.MAVEN_CENTRAL;

    /** Default-Timeout für Verbindung und Download. */
    public static final Duration DEFAULT_TIMEOUT = LibDownloader.DEFAULT_TIMEOUT;

    private final LibDownloader downloader;
    private final CloudLayout layout;

    /**
     * @param layout Cloud-Layout mit {@code lib/}
     */
    public DependencyDownloader(CloudLayout layout) {
        this(layout, MAVEN_CENTRAL);
    }

    /**
     * @param layout     Cloud-Layout mit {@code lib/}
     * @param repository Repository-Basis, z. B. Maven Central oder ein Mirror
     */
    public DependencyDownloader(CloudLayout layout, URI repository) {
        this(layout, repository, HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /**
     * @param layout     Cloud-Layout mit {@code lib/}
     * @param repository Repository-Basis
     * @param client     HTTP-Client, z. B. für Tests mit lokalem Server
     */
    public DependencyDownloader(CloudLayout layout, URI repository, HttpClient client) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.downloader = new LibDownloader(layout, repository, client, DEFAULT_TIMEOUT);
    }

    /**
     * Stellt sicher, dass alle Abhängigkeiten vorhanden und unverändert sind.
     *
     * @param dependencies benötigte Abhängigkeiten
     * @return das aktualisierte Manifest
     * @throws DownloadException bei Netzwerkfehler, HTTP-Fehler, falscher Prüfsumme oder Abbruch
     */
    public LibManifest ensure(Collection<Dependency> dependencies) {
        return ensure(dependencies, status -> {
        });
    }

    /**
     * Stellt alle Abhängigkeiten parallel auf Virtual Threads sicher und meldet
     * den Fortschritt nach jeder fertigen Datei. Die Reihenfolge im Manifest
     * bleibt die Reihenfolge der Eingabe, auch wenn die Downloads in anderer
     * Reihenfolge fertig werden.
     *
     * @param dependencies benötigte Abhängigkeiten
     * @param progress     Empfänger für den Fortschritt; läuft unter einem Lock und
     *                     darf deshalb nicht blockieren
     * @return das aktualisierte Manifest
     * @throws DownloadException bei Netzwerkfehler, HTTP-Fehler, falscher Prüfsumme oder Abbruch
     */
    public LibManifest ensure(Collection<Dependency> dependencies, Consumer<DownloadStatus> progress) {
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(progress, "progress");

        List<LibDownloader.Outcome> outcomes = downloader.download(dependencies, status -> {
            if (status.downloaded()) {
                log.info("{} geladen ({})", status.dependency().fileName(), status.dependency().coordinates());
            }
            progress.accept(status);
        });

        List<LibManifest.Entry> entries = new ArrayList<>(outcomes.size());
        for (LibDownloader.Outcome outcome : outcomes) {
            if (outcome.failed()) {
                throw outcome.error();
            }
            entries.add(entryFor(outcome));
        }

        LibManifest manifest = new LibManifest(entries);
        try {
            manifest.write(layout.lib());
        } catch (IOException e) {
            throw new DownloadException("Manifest konnte nicht geschrieben werden: " + layout.lib(), e);
        }
        return manifest;
    }

    /**
     * @param dependency Abhängigkeit
     * @return vollständige Download-URL
     */
    public URI uriOf(Dependency dependency) {
        return downloader.uriOf(dependency);
    }

    private static LibManifest.Entry entryFor(LibDownloader.Outcome outcome) {
        Dependency dependency = outcome.dependency();
        return new LibManifest.Entry(
                dependency.fileName(),
                dependency.groupId(),
                dependency.artifactId(),
                dependency.version(),
                dependency.sha256(),
                outcome.bytes());
    }

    /**
     * @param file Datei
     * @return SHA-256 als Hex
     * @throws IOException wenn die Datei nicht lesbar ist
     */
    static String sha256(Path file) throws IOException {
        return LibDownloader.sha256(file);
    }
}
