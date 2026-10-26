package dev.bytestorm.launcher.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lädt Plattform-Jars über die PaperMC-Fill-API nach {@code platforms/} (§13).
 *
 * <p>Unterstützt werden die Projekte, die Fill unter {@code /v3/projects/<project>}
 * anbietet, insbesondere {@code paper}, {@code velocity}, {@code folia} und
 * {@code waterfall}. Ohne Version wird die neueste Version mit einem stabilen
 * Build gewählt. Dateien heißen {@code <project>-<version>.jar}; vorhandene
 * Dateien werden gegen die von der API gemeldete SHA-256 geprüft und
 * wiederverwendet. Downloads laufen über eine temporäre Datei und werden erst
 * nach erfolgreicher Prüfung atomar verschoben.
 *
 * <p>Die API verlangt einen aussagekräftigen User-Agent mit Kontakt-URL. Der
 * Downloader setzt ihn auf jedem Request; der Build soll ihn bei Bedarf über
 * den Konstruktor ersetzen. Experimentelle Builds werden nie gewählt.
 */
@Slf4j
public final class PlatformDownloader {

    /** Standard-Basis der PaperMC-Fill-API. */
    public static final URI DEFAULT_API = URI.create("https://fill.papermc.io");

    /** User-Agent mit Projekt- und Kontaktangabe, wie von PaperMC verlangt. */
    public static final String USER_AGENT =
            "ByteStorm/1.0 (+https://github.com/bytestorm/ByteStorm)";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final Path directory;
    private final URI apiBase;
    private final HttpClient client;
    private final String userAgent;

    /**
     * @param directory Zielverzeichnis, üblicherweise {@code platforms/}
     */
    public PlatformDownloader(Path directory) {
        this(directory, DEFAULT_API, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), USER_AGENT);
    }

    /**
     * @param directory Zielverzeichnis
     * @param apiBase   API-Basis, z. B. für einen Mirror oder Tests
     */
    public PlatformDownloader(Path directory, URI apiBase) {
        this(directory, apiBase, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), USER_AGENT);
    }

    /**
     * @param directory Zielverzeichnis
     * @param apiBase   API-Basis
     * @param client    HTTP-Client
     * @param userAgent User-Agent mit Kontaktangabe
     */
    public PlatformDownloader(Path directory, URI apiBase, HttpClient client, String userAgent) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        String base = Objects.requireNonNull(apiBase, "apiBase").toString();
        this.apiBase = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
        this.client = Objects.requireNonNull(client, "client");
        this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
    }

    /**
     * Stellt die Plattform-Jar bereit.
     *
     * @param platform Projektname, z. B. {@code paper}
     * @param version  gewünschte Version oder {@code null}/leer für die neueste stabile
     * @return die geprüfte Jar unter {@code platforms/}
     * @throws PlatformDownloadException bei API-, Netzwerk-, Prüfsummen- oder Schreibfehlern
     */
    public Path ensure(String platform, String version) {
        String project = normalize(platform, "Projekt");
        String requested = version == null ? "" : version.trim();
        if (!requested.isEmpty()) {
            normalize(requested, "Version");
        }

        if (requested.isEmpty()) {
            for (String candidate : versions(project)) {
                Build build = stableBuild(project, candidate);
                if (build != null) {
                    return download(project, candidate, build);
                }
            }
            throw new PlatformDownloadException("Kein stabiler Build für Projekt '" + project + "' gefunden");
        }

        Build build = stableBuild(project, requested);
        if (build == null) {
            throw new PlatformDownloadException("Kein stabiler Build für '" + project + " " + requested + "' gefunden");
        }
        return download(project, requested, build);
    }

    /**
     * Listet die verfügbaren Versionen eines Projekts, neueste zuerst (§17).
     *
     * @param platform Projektname, z. B. {@code paper}
     * @return die von der API gemeldeten Versionen
     * @throws PlatformDownloadException bei API-, Netzwerk- oder Formatfehlern
     */
    public List<String> availableVersions(String platform) {
        return List.copyOf(versions(normalize(platform, "Projekt")));
    }

    /**
     * Sucht die neueste Version mit einem stabilen Build.
     *
     * @param platform Projektname
     * @return die Version, leer wenn kein stabiler Build existiert
     * @throws PlatformDownloadException bei API-, Netzwerk- oder Formatfehlern
     */
    public Optional<String> latestVersion(String platform) {
        String project = normalize(platform, "Projekt");
        for (String candidate : versions(project)) {
            if (stableBuild(project, candidate) != null) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private List<String> versions(String project) {
        JsonNode root = getJson(endpoint("/v3/projects/" + project));
        JsonNode versions = root.path("versions");
        if (!versions.isObject()) {
            throw new PlatformDownloadException("PaperMC-Antwort für '" + project + "' enthält keine Versionen");
        }
        Set<String> result = new LinkedHashSet<>();
        for (var entry : versions.properties()) {
            if (entry.getValue().isArray()) {
                entry.getValue().forEach(node -> {
                    if (node.isTextual() && !node.asText().isBlank()) {
                        result.add(node.asText());
                    }
                });
            }
        }
        if (result.isEmpty()) {
            throw new PlatformDownloadException("Keine Versionen für Projekt '" + project + "' gefunden");
        }
        return new ArrayList<>(result);
    }

    private Build stableBuild(String project, String version) {
        JsonNode root = getJson(endpoint("/v3/projects/" + project + "/versions/" + version + "/builds"));
        if (!root.isArray()) {
            throw new PlatformDownloadException("PaperMC-Antwort für '" + project + " " + version
                    + "' enthält keine Build-Liste");
        }
        for (JsonNode node : root) {
            if (!"STABLE".equalsIgnoreCase(node.path("channel").asText())) {
                continue;
            }
            JsonNode download = chooseDownload(node.path("downloads"));
            if (download == null) {
                continue;
            }
            String sha256 = download.path("checksums").path("sha256").asText("");
            String url = download.path("url").asText("");
            if (sha256.isBlank() || url.isBlank()) {
                throw new PlatformDownloadException("Download für '" + project + " " + version
                        + "' hat keine SHA-256 oder URL");
            }
            return new Build(URI.create(url), sha256, download.path("name").asText(""));
        }
        return null;
    }

    private static JsonNode chooseDownload(JsonNode downloads) {
        if (!downloads.isObject() || downloads.isEmpty()) {
            return null;
        }
        JsonNode preferred = downloads.get("server:default");
        if (preferred != null && preferred.isObject()) {
            return preferred;
        }
        for (var entry : downloads.properties()) {
            JsonNode candidate = entry.getValue();
            if (candidate.isObject()) {
                return candidate;
            }
        }
        return null;
    }

    private Path download(String project, String version, Build build) {
        String fileName = project + "-" + version + ".jar";
        Path target = directory.resolve(fileName);
        try {
            Files.createDirectories(directory);
            if (Files.isRegularFile(target) && sha256(target).equals(build.sha256())) {
                log.debug("{} ist vorhanden und geprüft", target.getFileName());
                return target;
            }
            if (Files.exists(target)) {
                log.warn("{} weicht von der erwarteten Prüfsumme ab — lade neu", target.getFileName());
            }

            Path temp = Files.createTempFile(directory, "." + fileName + "-", ".tmp");
            try {
                HttpRequest request = request(build.url())
                        .header("Accept", "application/java-archive")
                        .GET()
                        .build();
                log.info("Lade {} {} ({}) von {}", project, version, build.name(), build.url());
                HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(temp));
                if (response.statusCode() != 200) {
                    throw new PlatformDownloadException("HTTP " + response.statusCode()
                            + " für " + build.url());
                }
                String actual = sha256(temp);
                if (!actual.equals(build.sha256())) {
                    throw new PlatformDownloadException("Prüfsumme falsch für " + fileName
                            + ": erwartet " + build.sha256() + ", erhalten " + actual);
                }
                move(temp, target);
                log.info("{} geladen", target.getFileName());
                return target;
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformDownloadException("Download abgebrochen: " + project + " " + version, e);
        } catch (IOException e) {
            throw new PlatformDownloadException("Download fehlgeschlagen für " + project + " "
                    + version + ": " + e.getMessage(), e);
        }
    }

    private JsonNode getJson(URI uri) {
        HttpRequest request = request(uri).header("Accept", "application/json").GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new PlatformDownloadException("HTTP " + response.statusCode() + " für " + uri);
            }
            JsonNode root = MAPPER.readTree(response.body());
            if (root.isObject() && root.has("ok") && !root.path("ok").asBoolean(true)) {
                throw new PlatformDownloadException("PaperMC-API: "
                        + root.path("message").asText("unbekannter Fehler"));
            }
            return root;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformDownloadException("API-Abfrage abgebrochen: " + uri, e);
        } catch (IOException e) {
            throw new PlatformDownloadException("API-Abfrage fehlgeschlagen: " + uri
                    + " — " + e.getMessage(), e);
        }
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", userAgent);
    }

    private URI endpoint(String path) {
        return URI.create(apiBase + path);
    }

    private static String normalize(String value, String field) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SAFE_NAME.matcher(normalized).matches()) {
            throw new PlatformDownloadException("Ungültige " + field + ": '" + value + "'");
        }
        return normalized;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (var in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", e);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Build(URI url, String sha256, String name) {
    }
}
