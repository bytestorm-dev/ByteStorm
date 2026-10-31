package dev.bytestorm.launcher.download;

import dev.bytestorm.launcher.layout.CloudLayout;

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
 * Lädt fehlende Jars nach {@code lib/} — ohne Jackson und ohne SLF4J (§5.1).
 *
 * <p>Diese Klasse ist absichtlich von den Bibliotheken in {@code lib/} unabhängig:
 * Der Launcher ruft sie auf, bevor er den {@link dev.bytestorm.launcher.LibClassLoader}
 * öffnet. In diesem Moment stehen nur JDK-Klassen zur Verfügung, deshalb sprechen
 * {@link DependencyDownloader} (mit Manifest) und der Erststart über dieses
 * gemeinsame Herz.
 *
 * <p>Je Abhängigkeit: vorhandene Datei gegen die erwartete SHA-256 prüfen und
 * wiederverwenden, sonst über eine temporäre Datei laden und erst nach der
 * Prüfung atomar verschieben. Fehler werden je Abhängigkeit gesammelt, damit ein
 * fehlendes Netz alle offenen Downloads melden kann, statt beim ersten
 * abzubrechen.
 */
public final class LibDownloader {

    /** Basis-URL von Maven Central. */
    public static final URI MAVEN_CENTRAL = URI.create("https://repo1.maven.org/maven2/");

    /** Default-Timeout für Verbindung und Download. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Ergebnis einer Abhängigkeit.
     *
     * @param dependency Abhängigkeit
     * @param file       Zielpfad in {@code lib/}
     * @param bytes      Dateigröße, {@code 0} bei einem Fehler
     * @param downloaded {@code true}, wenn die Datei geladen wurde
     * @param error      Fehler oder {@code null}
     */
    public record Outcome(Dependency dependency, Path file, long bytes, boolean downloaded, DownloadException error) {

        /**
         * @param dependency Abhängigkeit
         * @param file       Zielpfad
         * @param bytes      Dateigröße
         * @param downloaded geladen oder wiederverwendet
         * @param error      Fehler oder {@code null}
         */
        public Outcome {
            Objects.requireNonNull(dependency, "dependency");
            Objects.requireNonNull(file, "file");
        }

        /**
         * @return {@code true}, wenn die Datei nicht bereitsteht
         */
        public boolean failed() {
            return error != null;
        }

        /**
         * @return Dateiname im {@code lib/}-Verzeichnis
         */
        public String fileName() {
            return dependency.fileName();
        }
    }

    private final CloudLayout layout;
    private final URI repository;
    private final HttpClient client;
    private final Duration timeout;

    /**
     * @param layout Cloud-Layout mit {@code lib/}
     */
    public LibDownloader(CloudLayout layout) {
        this(layout, MAVEN_CENTRAL);
    }

    /**
     * @param layout     Cloud-Layout mit {@code lib/}
     * @param repository Repository-Basis, z. B. Maven Central oder ein Mirror
     */
    public LibDownloader(CloudLayout layout, URI repository) {
        this(layout, repository, HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), DEFAULT_TIMEOUT);
    }

    /**
     * @param layout     Cloud-Layout mit {@code lib/}
     * @param repository Repository-Basis
     * @param client     HTTP-Client, z. B. für Tests mit lokalem Server
     * @param timeout    Timeout je Anfrage
     */
    public LibDownloader(CloudLayout layout, URI repository, HttpClient client, Duration timeout) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.repository = withTrailingSlash(Objects.requireNonNull(repository, "repository"));
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * Stellt alle Abhängigkeiten parallel auf Virtual Threads sicher. Ein
     * fehlgeschlagener Download beendet die anderen nicht; das Ergebnis nennt für
     * jede Abhängigkeit ihren Zustand in der Reihenfolge der Anfrage.
     *
     * @param dependencies benötigte Abhängigkeiten
     * @param progress     Empfänger für abgeschlossene Downloads
     * @return Ergebnis je Abhängigkeit
     * @throws DownloadException wenn {@code lib/} nicht angelegt werden kann oder der
     *                           aufrufende Thread unterbrochen wird
     */
    public List<Outcome> download(Collection<Dependency> dependencies, Consumer<DownloadStatus> progress) {
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
        if (ordered.isEmpty()) {
            return List.of();
        }

        int total = ordered.size();
        List<Outcome> outcomes = new ArrayList<>(total);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger completed = new AtomicInteger();
        Object progressLock = new Object();
        List<Future<Outcome>> futures = new ArrayList<>(total);
        try {
            for (Dependency dependency : ordered) {
                futures.add(executor.submit(() -> {
                    Outcome outcome = attempt(dependency);
                    if (!outcome.failed()) {
                        DownloadStatus status = new DownloadStatus(
                                completed.incrementAndGet(), total, dependency, outcome.downloaded());
                        synchronized (progressLock) {
                            progress.accept(status);
                        }
                    }
                    return outcome;
                }));
            }
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get());
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
        return List.copyOf(outcomes);
    }

    private Outcome attempt(Dependency dependency) {
        Path target = layout.lib().resolve(dependency.fileName());
        try {
            if (isUpToDate(target, dependency)) {
                return new Outcome(dependency, target, Files.size(target), false, null);
            }
            if (Files.exists(target)) {
                // Datei ist beschädigt oder veraltet: neu laden statt ihr zu vertrauen.
                Files.delete(target);
            }
            download(dependency, target);
            return new Outcome(dependency, target, Files.size(target), true, null);
        } catch (DownloadException e) {
            return new Outcome(dependency, target, 0, false, e);
        } catch (IOException | RuntimeException e) {
            return new Outcome(dependency, target, 0, false,
                    new DownloadException("Download fehlgeschlagen für " + dependency.coordinates()
                            + ": " + describe(e), e));
        }
    }

    private boolean isUpToDate(Path target, Dependency dependency) {
        if (!Files.isRegularFile(target)) {
            return false;
        }
        try {
            return sha256(target).equals(dependency.sha256());
        } catch (IOException e) {
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

    /**
     * @param dependency fehlende Abhängigkeit
     * @param uri        Quelle
     * @return Anleitung zum manuellen Ablegen
     */
    public String offlineMessage(Dependency dependency, URI uri) {
        Path target = layout.lib().resolve(dependency.fileName());
        String newline = System.lineSeparator();
        return "Keine Netzwerkverbindung: " + dependency.coordinates() + " fehlt in " + layout.lib()
                + " und konnte nicht geladen werden." + newline
                + "Bitte die Internetverbindung prüfen oder die Datei manuell ablegen:" + newline
                + "  Ziel:    " + target + newline
                + "  SHA-256: " + dependency.sha256() + newline
                + "  Quelle:  " + uri;
    }

    /**
     * @param dependency Abhängigkeit
     * @return vollständige Download-URL
     */
    public URI uriOf(Dependency dependency) {
        return repository.resolve(dependency.mavenPath());
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
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
