package dev.bytestorm.core.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Aktualisiert die Cloud aus den Releases des Projekts (§17).
 *
 * <p>Quelle ist die GitHub-Releases-API. Ein Release gilt als einspielbar, wenn es
 * ein Zip-Artefakt trägt — genau das legt die GitHub-Action ab
 * ({@code bytestorm-<version>.zip} mit {@code bytestorm.jar} und {@code lib/}).
 *
 * <p>Eingespielt wird in drei Schritten: Zip in {@code update/} laden, in einen
 * Staging-Ordner entpacken und erst dann die Dateien an ihren Platz kopieren.
 * Vorhandene Dateien wandern vorher nach {@code update/backup/}, damit ein
 * misslungenes Update nachvollziehbar bleibt. Entpackt werden nur
 * {@code bytestorm.jar} und {@code lib/*.jar}; alles andere im Archiv wird
 * übergangen, Pfade mit {@code ..} werden abgewiesen.
 *
 * <p>Die laufende Cloud tauscht damit ihre Jars aus, benutzt aber weiterhin die
 * geladenen Klassen — die neue Version greift erst nach einem Neustart.
 */
@Slf4j
public final class Updater {

    /** Repository mit den Releases, wenn nichts anderes angegeben wird. */
    public static final String DEFAULT_REPOSITORY = "bytestorm-dev/ByteStorm";

    /** Basis der GitHub-API. */
    public static final URI DEFAULT_API = URI.create("https://api.github.com");

    /** User-Agent; die GitHub-API weist Anfragen ohne ihn ab. */
    public static final String USER_AGENT = "ByteStorm-Cloud (+https://github.com/bytestorm-dev/ByteStorm)";

    /** Dateiname des Einstiegs-Jars in der Distribution. */
    public static final String LAUNCHER_JAR = "bytestorm.jar";

    /** Unterordner der Distribution mit den Laufzeit-Jars. */
    public static final String LIB_DIRECTORY = "lib";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(300);
    private static final int RELEASES_PER_PAGE = 50;
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final Path cloudDirectory;
    private final String repository;
    private final URI apiBase;
    private final HttpClient client;

    /**
     * @param cloudDirectory Cloud-Verzeichnis mit {@code bytestorm.jar} und {@code lib/}
     */
    public Updater(Path cloudDirectory) {
        this(cloudDirectory, DEFAULT_REPOSITORY);
    }

    /**
     * @param cloudDirectory Cloud-Verzeichnis
     * @param repository     Repository als {@code owner/name}
     */
    public Updater(Path cloudDirectory, String repository) {
        this(cloudDirectory, repository, DEFAULT_API, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /**
     * @param cloudDirectory Cloud-Verzeichnis
     * @param repository     Repository als {@code owner/name}
     * @param apiBase        API-Basis, z. B. für Tests
     * @param client         HTTP-Client
     */
    public Updater(Path cloudDirectory, String repository, URI apiBase, HttpClient client) {
        this.cloudDirectory = Objects.requireNonNull(cloudDirectory, "cloudDirectory")
                .toAbsolutePath().normalize();
        this.repository = requireRepository(repository);
        String base = Objects.requireNonNull(apiBase, "apiBase").toString();
        this.apiBase = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * @return das konfigurierte Repository
     */
    public String repository() {
        return repository;
    }

    /**
     * Liest die Releases, neueste zuerst; Entwürfe werden übersprungen.
     *
     * @return veröffentlichte Releases
     * @throws UpdateException bei Netzwerk-, HTTP- oder Formatfehlern
     */
    public List<Release> releases() {
        JsonNode root = getJson("/repos/" + repository + "/releases?per_page=" + RELEASES_PER_PAGE);
        if (!root.isArray()) {
            throw new UpdateException("GitHub-Antwort für '" + repository + "' enthält keine Release-Liste");
        }

        List<Release> releases = new ArrayList<>();
        for (JsonNode node : root) {
            if (node.path("draft").asBoolean(false)) {
                continue;
            }
            String tag = node.path("tag_name").asText("").trim();
            if (tag.isEmpty()) {
                continue;
            }
            String version = stripPrefix(tag);
            Asset asset = asset(node.path("assets"));
            releases.add(new Release(tag, version,
                    node.path("name").asText("").trim(),
                    node.path("prerelease").asBoolean(false),
                    node.path("html_url").asText(""),
                    publishedAt(node.path("published_at").asText("")),
                    asset == null ? null : asset.url(),
                    asset == null ? "" : asset.name()));
        }
        releases.sort(Comparator.comparing(Release::publishedAt).reversed());
        return List.copyOf(releases);
    }

    /**
     * @return das neueste Release ohne Vorabversion, falls vorhanden
     */
    public Optional<Release> latest() {
        return releases().stream()
                .filter(release -> !release.prerelease())
                .findFirst();
    }

    /**
     * @param version Version mit oder ohne führendes {@code v}
     * @return das Release zu dieser Version
     */
    public Optional<Release> byVersion(String version) {
        String wanted = stripPrefix(version == null ? "" : version.trim());
        return releases().stream()
                .filter(release -> release.version().equalsIgnoreCase(wanted))
                .findFirst();
    }

    /**
     * Sucht ein Release, das neuer als die laufende Version ist.
     *
     * <p>Der Vergleich verhindert die falsche Meldung „Update verfügbar“, wenn
     * ein Entwicklungsstand (etwa {@code 2.0-SNAPSHOT}) neuer als das letzte
     * veröffentlichte Release ist.
     *
     * @param runningVersion laufende Version, mit oder ohne führendes {@code v}
     * @return das neueste Release, das neuer ist, sonst leer
     */
    public Optional<Release> newerThan(String runningVersion) {
        if (runningVersion == null || runningVersion.isBlank()) {
            return Optional.empty();
        }
        return latest().filter(release -> CloudVersion.isNewer(release.version(), runningVersion));
    }

    /**
     * Spielt ein Release ein.
     *
     * @param release Release mit Zip-Artefakt
     * @return Anzahl der kopierten Dateien
     * @throws UpdateException wenn das Artefakt fehlt, nicht ladbar ist oder das Archiv unbrauchbar ist
     */
    public int install(Release release) {
        Objects.requireNonNull(release, "release");
        if (release.asset() == null) {
            throw new UpdateException("Release " + release.tag() + " hat kein Zip-Artefakt");
        }

        Path updateDirectory = cloudDirectory.resolve("update");
        try {
            Files.createDirectories(updateDirectory);
            Path archive = Files.createTempFile(updateDirectory, ".bytestorm-", ".zip");
            try {
                download(release.asset(), archive);
                Path staging = unpack(archive, updateDirectory);
                try {
                    return copyIntoPlace(staging);
                } finally {
                    deleteRecursively(staging);
                }
            } finally {
                Files.deleteIfExists(archive);
            }
        } catch (IOException e) {
            throw new UpdateException("Update " + release.tag() + " fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    private void download(URI url, Path target) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/octet-stream")
                .GET()
                .build();
        HttpResponse<Path> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException("Download abgebrochen: " + url, e);
        }
        if (response.statusCode() != 200) {
            throw new UpdateException("HTTP " + response.statusCode() + " für " + url);
        }
        log.info("Release-Artefakt geladen: {} ({} Bytes)", url, Files.size(target));
    }

    private Path unpack(Path archive, Path updateDirectory) throws IOException {
        Path staging = Files.createTempDirectory(updateDirectory, "staging-");
        int entries = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory() || !isDistributionFile(entry.getName())) {
                    continue;
                }
                Path target = safeResolve(staging, entry.getName());
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                entries++;
            }
        } catch (IOException e) {
            deleteRecursively(staging);
            throw e;
        }
        if (entries == 0) {
            deleteRecursively(staging);
            throw new UpdateException("Archiv enthält weder "
                    + LAUNCHER_JAR + " noch Jars unter " + LIB_DIRECTORY + "/");
        }
        if (!Files.isRegularFile(staging.resolve(LAUNCHER_JAR))) {
            deleteRecursively(staging);
            throw new UpdateException("Archiv enthält kein " + LAUNCHER_JAR);
        }
        return staging;
    }

    private int copyIntoPlace(Path staging) throws IOException {
        Path backup = cloudDirectory.resolve("update").resolve("backup");
        int copied = 0;
        try (Stream<Path> files = Files.walk(staging)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = staging.relativize(file);
                Path target = cloudDirectory.resolve(relative);
                if (Files.isRegularFile(target)) {
                    Path savedTo = backup.resolve(relative);
                    Files.createDirectories(savedTo.getParent());
                    Files.copy(target, savedTo, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }
        log.info("{} Dateien ersetzt (vorherige Fassung unter {})", copied, backup);
        return copied;
    }

    private JsonNode getJson(String path) {
        HttpRequest request = HttpRequest.newBuilder(endpoint(path))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new UpdateException("Repository oder Release nicht gefunden: " + repository);
            }
            if (response.statusCode() == 403) {
                throw new UpdateException("GitHub-API abgewiesen (HTTP 403) — Rate-Limit oder fehlender Zugriff auf "
                        + repository);
            }
            if (response.statusCode() != 200) {
                throw new UpdateException("HTTP " + response.statusCode() + " für " + endpoint(path));
            }
            return MAPPER.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException("API-Abfrage abgebrochen: " + path, e);
        } catch (IOException e) {
            throw new UpdateException("API-Abfrage fehlgeschlagen: " + path + " — " + e.getMessage(), e);
        }
    }

    private URI endpoint(String path) {
        return URI.create(apiBase + path);
    }

    /**
     * @param name Eintragsname aus dem Archiv
     * @return {@code true}, wenn der Eintrag Teil der Distribution ist
     */
    private static boolean isDistributionFile(String name) {
        String normalized = name.replace('\\', '/');
        if (normalized.equals(LAUNCHER_JAR)) {
            return true;
        }
        return normalized.startsWith(LIB_DIRECTORY + "/")
                && normalized.toLowerCase(Locale.ROOT).endsWith(".jar")
                && !normalized.endsWith("/");
    }

    private static Path safeResolve(Path root, String name) {
        Path target = root.resolve(name.replace('\\', '/')).normalize();
        if (!target.startsWith(root)) {
            throw new UpdateException("Archiv enthält einen unzulässigen Pfad: " + name);
        }
        return target;
    }

    private static void deleteRecursively(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            log.warn("Aufräumen fehlgeschlagen für {}: {}", directory, e.getMessage());
        }
    }

    private static Asset asset(JsonNode assets) {
        if (!assets.isArray()) {
            return null;
        }
        for (JsonNode node : assets) {
            String name = node.path("name").asText("");
            String url = node.path("browser_download_url").asText("");
            if (name.toLowerCase(Locale.ROOT).endsWith(".zip") && !url.isBlank()) {
                return new Asset(name, URI.create(url));
            }
        }
        return null;
    }

    private static Instant publishedAt(String value) {
        try {
            return value == null || value.isBlank() ? Instant.EPOCH : Instant.parse(value.trim());
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }

    private static String stripPrefix(String version) {
        String trimmed = version == null ? "" : version.trim();
        return trimmed.startsWith("v") || trimmed.startsWith("V") ? trimmed.substring(1) : trimmed;
    }

    private static String requireRepository(String repository) {
        Objects.requireNonNull(repository, "repository");
        String trimmed = repository.trim();
        if (!trimmed.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("repository muss die Form owner/name haben: " + repository);
        }
        return trimmed;
    }

    /**
     * Ein veröffentlichtes Release.
     *
     * @param tag         Tag, z. B. {@code v1.0-SNAPSHOT}
     * @param version     Tag ohne führendes {@code v}
     * @param name        Titel des Releases
     * @param prerelease Vorabversion (Beta)
     * @param url         Seite des Releases
     * @param publishedAt Veröffentlichung
     * @param asset       Zip-Artefakt oder {@code null}
     * @param assetName   Dateiname des Artefakts, leer ohne Artefakt
     */
    public record Release(String tag, String version, String name, boolean prerelease,
                          String url, Instant publishedAt, URI asset, String assetName) {

        /**
         * @return {@code true}, wenn das Release ein Zip-Artefakt trägt
         */
        public boolean installable() {
            return asset != null;
        }
    }

    private record Asset(String name, URI url) {
    }
}
