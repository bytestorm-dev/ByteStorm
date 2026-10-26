package dev.bytestorm.launcher.download;

import dev.bytestorm.launcher.layout.CloudLayout;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Lädt fehlende Abhängigkeiten nach {@code lib/} (§5.1).
 *
 * <p>Die Abhängigkeiten eines Laufs werden parallel auf Virtual Threads geprüft
 * und geladen; der Fortschritt wird über {@link DownloadStatus} gemeldet, das
 * Manifest bleibt in der Reihenfolge der Anfrage.
 *
 * <p>Ablauf je Abhängigkeit: vorhandene Datei gegen die erwartete SHA-256
 * prüfen und wiederverwenden, sonst herunterladen. Download und Prüfsumme
 * laufen über eine temporäre Datei; erst nach erfolgreicher Prüfung wird sie
 * atomar an ihren Platz verschoben. Eine falsche Prüfsumme oder ein HTTP-Fehler
 * hinterlässt daher weder eine halbe Jar noch eine {@code .tmp}-Datei.
 *
 * <p>Das Manifest wird erst geschrieben, wenn alle Abhängigkeiten des Aufrufs
 * vorliegen. Bereits geladene, gültige Dateien bleiben bei einem Abbruch
 * liegen und werden beim nächsten Lauf wiederverwendet.
 */
@Slf4j
public final class DependencyDownloader {

    /** Basis-URL von Maven Central. */
    public static final URI MAVEN_CENTRAL = URI.create("https://repo1.maven.org/maven2/");

    /** Default-Timeout für Verbindung und Download. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final CloudLayout layout;
    private final URI repository;
    private final HttpClient client;
    private final Duration timeout;

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
        this.repository = withTrailingSlash(Objects.requireNonNull(repository, "repository"));
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = DEFAULT_TIMEOUT;
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

        List<Dependency> ordered = new ArrayList<>(dependencies.size());
        for (Dependency dependency : dependencies) {
            ordered.add(Objects.requireNonNull(dependency, "dependency"));
        }
        try {
            Files.createDirectories(layout.lib());
        } catch (IOException e) {
            throw new DownloadException("lib-Verzeichnis nicht anlegbar: " + layout.lib(), e);
        }

        int total = ordered.size();
        List<LibManifest.Entry> entries = new ArrayList<>(total);
        if (total > 0) {
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            AtomicInteger completed = new AtomicInteger();
            Object progressLock = new Object();
            List<Future<Outcome>> futures = new ArrayList<>(total);
            try {
                for (Dependency dependency : ordered) {
                    futures.add(executor.submit(() -> {
                        Outcome outcome = ensureOne(dependency);
                        DownloadStatus status = new DownloadStatus(
                                completed.incrementAndGet(), total, dependency, outcome.downloaded());
                        synchronized (progressLock) {
                            progress.accept(status);
                        }
                        return outcome;
                    }));
                }
                for (Future<Outcome> future : futures) {
                    entries.add(future.get().entry());
                }
            } catch (InterruptedException e) {
                futures.forEach(future -> future.cancel(true));
                Thread.currentThread().interrupt();
                throw new DownloadException("Download abgebrochen", e);
            } catch (ExecutionException e) {
                futures.forEach(future -> future.cancel(true));
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof DownloadException downloadException) {
                    throw downloadException;
                }
                throw new DownloadException("Download fehlgeschlagen: " + describe(cause), cause);
            } finally {
                executor.shutdownNow();
            }
        }

        LibManifest manifest = new LibManifest(entries);
        try {
            manifest.write(layout.lib());
        } catch (IOException e) {
            throw new DownloadException("Manifest konnte nicht geschrieben werden: " + layout.lib(), e);
        }
        return manifest;
    }

    private Outcome ensureOne(Dependency dependency) {
        Path target = layout.lib().resolve(dependency.fileName());
        if (isUpToDate(target, dependency)) {
            log.debug("{} ist vorhanden und geprüft", dependency.fileName());
            return new Outcome(entryFor(dependency, target), false);
        }
        if (Files.exists(target)) {
            log.warn("{} weicht von der erwarteten Prüfsumme ab — lade neu", dependency.fileName());
        }
        download(dependency, target);
        return new Outcome(entryFor(dependency, target), true);
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record Outcome(LibManifest.Entry entry, boolean downloaded) {
    }

    private boolean isUpToDate(Path target, Dependency dependency) {
        if (!Files.isRegularFile(target)) {
            return false;
        }
        try {
            return sha256(target).equals(dependency.sha256());
        } catch (IOException e) {
            log.warn("Prüfsumme von {} nicht lesbar — lade neu", target, e);
            return false;
        }
    }

    private void download(Dependency dependency, Path target) {
        URI uri = repository.resolve(dependency.mavenPath());
        Path temp = layout.lib().resolve(dependency.fileName() + ".tmp");
        try {
            Files.deleteIfExists(temp);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .GET()
                    .build();
            log.info("Lade {} von {}", dependency.fileName(), uri);

            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(temp));
            if (response.statusCode() != 200) {
                throw new DownloadException("HTTP " + response.statusCode() + " für " + uri);
            }

            String actual = sha256(temp);
            if (!actual.equals(dependency.sha256())) {
                throw new DownloadException("Prüfsumme falsch für " + dependency.fileName()
                        + ": erwartet " + dependency.sha256() + ", erhalten " + actual);
            }

            move(temp, target);
            log.info("{} geladen ({})", dependency.fileName(), dependency.coordinates());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadException("Download abgebrochen: " + dependency.coordinates(), e);
        } catch (IOException e) {
            if (isOffline(e)) {
                throw new OfflineException(offlineMessage(dependency, uri), e);
            }
            throw new DownloadException("Download fehlgeschlagen für " + dependency.coordinates()
                    + ": " + e.getMessage(), e);
        } finally {
            deleteQuietly(temp);
        }
    }

    /**
     * @param error Fehler beim Verbindungsaufbau oder Warten auf die Antwort
     * @return {@code true}, wenn der Fehler auf eine fehlende Netzwerkverbindung hindeutet
     */
    private static boolean isOffline(Throwable error) {
        for (Throwable current = error; current != null && current != current.getCause(); current = current.getCause()) {
            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException
                    || current instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private String offlineMessage(Dependency dependency, URI uri) {
        Path target = layout.lib().resolve(dependency.fileName());
        String newline = System.lineSeparator();
        return "Keine Netzwerkverbindung: " + dependency.coordinates() + " fehlt in " + layout.lib()
                + " und konnte nicht geladen werden." + newline
                + "Bitte die Internetverbindung prüfen oder die Datei manuell ablegen:" + newline
                + "  Ziel:    " + target + newline
                + "  SHA-256: " + dependency.sha256() + newline
                + "  Quelle:  " + uri;
    }

    private static LibManifest.Entry entryFor(Dependency dependency, Path target) {
        try {
            return new LibManifest.Entry(
                    dependency.fileName(),
                    dependency.groupId(),
                    dependency.artifactId(),
                    dependency.version(),
                    dependency.sha256(),
                    Files.size(target));
        } catch (IOException e) {
            throw new DownloadException("Dateigröße nicht lesbar: " + target, e);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Eine liegengebliebene .tmp-Datei ist harmlos und wird beim nächsten Lauf ersetzt.
        }
    }

    /**
     * @param file Datei
     * @return SHA-256 als Hex
     * @throws IOException wenn die Datei nicht lesbar ist
     */
    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static URI withTrailingSlash(URI repository) {
        String value = repository.toString();
        return value.endsWith("/") ? repository : URI.create(value + "/");
    }
}
