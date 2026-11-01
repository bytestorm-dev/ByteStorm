package dev.bytestorm.launcher.luckperms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.bytestorm.core.config.MongoSettings;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.proto.ServiceGroup;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stellt LuckPerms je Service bereit und verbindet es mit MongoDB und Redis (§13).
 *
 * <p>Ein Netzwerk ohne gemeinsame Rechte-Datenbank ist schnell gebaut: Jeder
 * Service hätte seine eigene {@code config.yml} mit H2-Datei, und ein Rang auf
 * der Lobby gilt nicht auf dem Proxy. Deshalb legt diese Klasse zwei Dinge an,
 * bevor ein Service startet:
 *
 * <ol>
 *   <li>die zu seiner Plattform passende LuckPerms-Jar unter {@code plugins/}
 *       bzw. {@code mods/} — Papier, Spigot und Velocity haben eigene Pakete;</li>
 *   <li>eine {@code config.yml} (bzw. {@code luckperms.conf} auf Mod-Loadern),
 *       die {@code storage-method: mongodb} mit dem Connection-String der Cloud
 *       und {@code messaging-service: redis} mit deren Redis nutzt.</li>
 * </ol>
 *
 * <p>Der Collection-Prefix {@value #COLLECTION_PREFIX} hält LuckPerms von den
 * ByteStorm-Collections derselben Datenbank getrennt — ohne ihn würde
 * LuckPerms' {@code groups}-Collection die der Cloud überschreiben.
 *
 * <p>Vorhandene Konfigurationen bleiben unangetastet: Wer Rechte-Einstellungen
 * angepasst hat, soll sie nach einem Neustart wiederfinden. Nur die Jar wird
 * ersetzt, wenn LuckPerms eine neuere Fassung meldet.
 *
 * <p>Die Metadaten-API liefert keine Prüfsummen; deshalb wird die Quelle je
 * Plattform in {@code luckperms-<key>.jar.url} vermerkt und die vorhandene Datei
 * nur wiederverwendet, solange sie zu genau dieser URL gehört.
 */
@Slf4j
public final class LuckPermsInstaller {

    /** Basis der LuckPerms-Metadaten-API. */
    public static final URI DEFAULT_METADATA = URI.create("https://metadata.luckperms.net");

    /** Pfad der Download-Liste unterhalb der Metadaten-Basis. */
    public static final String DOWNLOADS_PATH = "/data/downloads";

    /** User-Agent mit Kontaktangabe. */
    public static final String USER_AGENT =
            "ByteStorm/1.0 (+https://github.com/bytestorm/ByteStorm)";

    /** Prefix aller LuckPerms-Collections in der geteilten Datenbank. */
    public static final String COLLECTION_PREFIX = "luckperms_";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final Path cache;
    private final URI metadata;
    private final HttpClient client;
    private final String userAgent;
    private final Map<LuckPermsPlatform, String> urls = new ConcurrentHashMap<>();

    /**
     * @param cacheDirectory Ablage der geladenen Jars, üblicherweise {@code platforms/luckperms}
     */
    public LuckPermsInstaller(Path cacheDirectory) {
        this(cacheDirectory, DEFAULT_METADATA, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), USER_AGENT);
    }

    /**
     * @param cacheDirectory Ablage der geladenen Jars
     * @param metadata       Metadaten-Basis, z. B. für Tests oder einen Mirror
     * @param client         HTTP-Client
     * @param userAgent      User-Agent
     */
    public LuckPermsInstaller(Path cacheDirectory, URI metadata, HttpClient client, String userAgent) {
        this.cache = Objects.requireNonNull(cacheDirectory, "cacheDirectory").toAbsolutePath().normalize();
        String base = Objects.requireNonNull(metadata, "metadata").toString();
        this.metadata = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
        this.client = Objects.requireNonNull(client, "client");
        this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
    }

    /**
     * Legt LuckPerms im Service-Ordner an, sofern die Plattform unterstützt wird.
     *
     * <p>Die Jar kommt aus dem Cache und wird nur kopiert, wenn sie im Service
     * fehlt oder eine andere Größe hat. Die Konfiguration entsteht ausschließlich,
     * wenn noch keine vorhanden ist.
     *
     * @param group            Gruppe mit der Plattform
     * @param serviceDirectory Arbeitsordner des Services
     * @param mongo            MongoDB der Cloud
     * @param redis            Redis der Cloud
     * @return die platzierte Jar, leer wenn LuckPerms die Plattform nicht kennt
     * @throws LuckPermsException bei Netzwerk-, API- oder Schreibfehlern
     */
    public Optional<Path> install(ServiceGroup group, Path serviceDirectory,
                                  MongoSettings mongo, RedisSettings redis) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(serviceDirectory, "serviceDirectory");
        Objects.requireNonNull(mongo, "mongo");
        Objects.requireNonNull(redis, "redis");

        Optional<LuckPermsPlatform> platform = LuckPermsPlatform.forCloudPlatform(group.getPlatform());
        if (platform.isEmpty()) {
            log.debug("Für Plattform '{}' gibt es kein LuckPerms-Paket", group.getPlatform());
            return Optional.empty();
        }
        LuckPermsPlatform target = platform.get();
        Path directory = serviceDirectory.toAbsolutePath().normalize();
        Path jar = ensureJar(target, group.getName());
        Path pluginDirectory = directory.resolve(target.folder());
        Path placed = pluginDirectory.resolve(target.jarName());

        try {
            Files.createDirectories(pluginDirectory);
            if (!sameContent(jar, placed)) {
                Files.copy(jar, placed, StandardCopyOption.REPLACE_EXISTING);
                log.info("LuckPerms für '{}' bereitgestellt ({}, {})",
                        group.getName(), target.label(), placed.getFileName());
            }
            writeConfig(target, directory, mongo, redis, group.getName());
            return Optional.of(placed);
        } catch (IOException e) {
            throw new LuckPermsException("LuckPerms für '" + group.getName()
                    + "' nicht platzierbar in " + pluginDirectory + ": " + e.getMessage(), e);
        }
    }

    /**
     * Stellt die Jar im Cache sicher.
     *
     * @param platform Plattform
     * @param service  Service-Name, nur für Fehlermeldungen
     * @return die geprüfte Jar im Cache
     */
    private Path ensureJar(LuckPermsPlatform platform, String service) {
        Path jar = cache.resolve("luckperms-" + platform.key() + ".jar");
        Path marker = cache.resolve("luckperms-" + platform.key() + ".jar.url");
        String url = urls.computeIfAbsent(platform, this::downloadUrl);
        try {
            Files.createDirectories(cache);
            if (Files.isRegularFile(jar) && url.equals(readMarker(marker))) {
                return jar;
            }
            download(platform, url, jar);
            Files.writeString(marker, url, StandardCharsets.UTF_8);
            return jar;
        } catch (IOException e) {
            throw new LuckPermsException("LuckPerms (" + platform.label() + ") für '" + service
                    + "' nicht ladbar: " + e.getMessage(), e);
        }
    }

    /**
     * @param platform Plattform
     * @return Download-URL aus der Metadaten-API
     */
    private String downloadUrl(LuckPermsPlatform platform) {
        URI uri = URI.create(metadata + DOWNLOADS_PATH);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new LuckPermsException("HTTP " + response.statusCode()
                        + " für " + uri + " (" + platform.key() + ")");
            }
            JsonNode downloads = MAPPER.readTree(response.body()).path("downloads");
            String url = downloads.path(platform.key()).asText("");
            if (url.isBlank()) {
                throw new LuckPermsException("LuckPerms bietet kein Paket für '"
                        + platform.key() + "' an (" + uri + ")");
            }
            URI source = URI.create(url);
            if (!"https".equalsIgnoreCase(source.getScheme()) && !"http".equalsIgnoreCase(source.getScheme())) {
                throw new LuckPermsException("Unerwartete LuckPerms-Quelle für '"
                        + platform.key() + "': " + url);
            }
            return url;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LuckPermsException("LuckPerms-Download abgebrochen (" + platform.key() + ")", e);
        } catch (IOException e) {
            throw new LuckPermsException("LuckPerms-Metadaten nicht lesbar (" + platform.key()
                    + "): " + e.getMessage(), e);
        }
    }

    private void download(LuckPermsPlatform platform, String url, Path jar) throws IOException {
        URI source = URI.create(url);
        Path temp = Files.createTempFile(cache, ".luckperms-" + platform.key() + "-", ".tmp");
        try {
            HttpRequest request = HttpRequest.newBuilder(source)
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/java-archive")
                    .GET()
                    .build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(temp));
            if (response.statusCode() != 200) {
                throw new LuckPermsException("HTTP " + response.statusCode() + " für " + source);
            }
            if (Files.size(temp) == 0) {
                throw new LuckPermsException("Leere LuckPerms-Datei von " + source);
            }
            move(temp, jar);
            log.info("LuckPerms geladen ({}, {})", platform.label(), Files.size(jar));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LuckPermsException("LuckPerms-Download abgebrochen: " + source, e);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Schreibt {@code config.yml} bzw. {@code luckperms.conf}, wenn sie fehlt.
     */
    private void writeConfig(LuckPermsPlatform platform, Path directory,
                             MongoSettings mongo, RedisSettings redis, String service) throws IOException {
        Path config = directory.resolve(platform.configPath());
        if (Files.exists(config)) {
            log.debug("LuckPerms-Konfiguration für '{}' vorhanden, bleibt unverändert", service);
            return;
        }
        Files.createDirectories(config.getParent());
        Files.writeString(config, platform.yaml() ? yaml(mongo, redis) : hocon(mongo, redis),
                StandardCharsets.UTF_8);
        log.info("LuckPerms für '{}' mit MongoDB {} und Redis {} verbunden",
                service, mongo.database(), redis);
    }

    /**
     * @param mongo MongoDB der Cloud
     * @param redis Redis der Cloud
     * @return YAML für Bukkit, BungeeCord und Velocity
     */
    static String yaml(MongoSettings mongo, RedisSettings redis) {
        Connection connection = Connection.of(mongo, redis);
        return """
                # Von ByteStorm erzeugt: LuckPerms speichert in der MongoDB der Cloud und
                # synchronisiert sich über deren Redis. Anpassungen bleiben bestehen — die
                # Datei wird nur angelegt, wenn sie fehlt.
                server: global
                storage-method: mongodb
                data:
                  address: %s
                  database: %s
                  username: %s
                  password: %s
                  mongodb-collection-prefix: %s
                  mongodb-connection-uri: %s
                messaging-service: redis
                redis:
                  enabled: true
                  address: %s
                  username: ''
                  password: %s
                """.formatted(
                quote(connection.mongoAddress()),
                quote(connection.mongoDatabase()),
                quote(connection.mongoUsername()),
                quote(connection.mongoPassword()),
                quote(COLLECTION_PREFIX),
                quote(connection.mongoUri()),
                quote(connection.redisAddress()),
                quote(connection.redisPassword()));
    }

    /**
     * @param mongo MongoDB der Cloud
     * @param redis Redis der Cloud
     * @return HOCON für Fabric, Forge, NeoForge und Sponge
     */
    static String hocon(MongoSettings mongo, RedisSettings redis) {
        Connection connection = Connection.of(mongo, redis);
        return """
                # Von ByteStorm erzeugt: LuckPerms speichert in der MongoDB der Cloud und
                # synchronisiert sich über deren Redis. Anpassungen bleiben bestehen — die
                # Datei wird nur angelegt, wenn sie fehlt.
                server="global"
                storage-method="mongodb"
                data {
                  address=%s
                  database=%s
                  username=%s
                  password=%s
                  mongodb-collection-prefix=%s
                  mongodb-connection-uri=%s
                }
                messaging-service="redis"
                redis {
                  enabled=true
                  address=%s
                  username=""
                  password=%s
                }
                """.formatted(
                quote(connection.mongoAddress()),
                quote(connection.mongoDatabase()),
                quote(connection.mongoUsername()),
                quote(connection.mongoPassword()),
                quote(COLLECTION_PREFIX),
                quote(connection.mongoUri()),
                quote(connection.redisAddress()),
                quote(connection.redisPassword()));
    }

    /**
     * Verbindungsdaten so aufbereitet, wie LuckPerms sie in seiner Konfiguration
     * erwartet.
     *
     * @param mongoAddress  {@code host:port}
     * @param mongoDatabase Datenbank
     * @param mongoUsername Benutzer, leer wenn die URI keine nennt
     * @param mongoPassword Passwort, leer wenn die URI keines nennt
     * @param mongoUri      vollständiger Connection-String
     * @param redisAddress  {@code host:port}
     * @param redisPassword Passwort, leer wenn keines gesetzt ist
     */
    record Connection(String mongoAddress, String mongoDatabase, String mongoUsername,
                      String mongoPassword, String mongoUri,
                      String redisAddress, String redisPassword) {

        /**
         * @param mongo MongoDB der Cloud
         * @param redis Redis der Cloud
         * @return die Werte für {@link #yaml} und {@link #hocon}
         */
        static Connection of(MongoSettings mongo, RedisSettings redis) {
            MongoUri mongoUri = MongoUri.parse(mongo.uri());
            RedisUri redisUri = RedisUri.parse(redis.address(), redis.password());
            return new Connection(mongoUri.address(), mongo.database(),
                    mongoUri.username(), mongoUri.password(), mongo.uri(),
                    redisUri.address(), redisUri.password());
        }
    }

    /**
     * Zerlegt {@code mongodb://user:pass@host:27017/db} in die Einzelfelder.
     */
    private record MongoUri(String address, String username, String password) {

        static MongoUri parse(String uri) {
            String authority = uri == null ? "" : uri.trim();
            int scheme = authority.indexOf("://");
            if (scheme >= 0) {
                authority = authority.substring(scheme + 3);
            }
            int end = indexOfAny(authority, '/', '?');
            if (end >= 0) {
                authority = authority.substring(0, end);
            }
            String username = "";
            String password = "";
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                String userinfo = authority.substring(0, at);
                authority = authority.substring(at + 1);
                int colon = userinfo.indexOf(':');
                username = decode(colon < 0 ? userinfo : userinfo.substring(0, colon));
                password = colon < 0 ? "" : decode(userinfo.substring(colon + 1));
            }
            int comma = authority.indexOf(',');
            if (comma >= 0) {
                // LuckPerms erwartet einen Host; bei Replica-Sets greift der Connection-String.
                authority = authority.substring(0, comma);
            }
            if (authority.isBlank()) {
                authority = "localhost:27017";
            }
            return new MongoUri(authority, username, password);
        }
    }

    /**
     * Zerlegt {@code redis://:pass@host:6379/0} in Adresse und Passwort.
     */
    private record RedisUri(String address, String password) {

        static RedisUri parse(String address, String configuredPassword) {
            String rest = address == null ? "" : address.trim();
            int scheme = rest.indexOf("://");
            if (scheme >= 0) {
                rest = rest.substring(scheme + 3);
            }
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                rest = rest.substring(0, slash);
            }
            String password = configuredPassword == null ? "" : configuredPassword;
            int at = rest.lastIndexOf('@');
            if (at >= 0) {
                String userinfo = rest.substring(0, at);
                rest = rest.substring(at + 1);
                int colon = userinfo.indexOf(':');
                if (password.isEmpty()) {
                    password = colon < 0 ? "" : decode(userinfo.substring(colon + 1));
                }
            }
            if (rest.isBlank()) {
                rest = "localhost";
            }
            return new RedisUri(rest, password);
        }
    }

    private static boolean sameContent(Path first, Path second) {
        try {
            return Files.isRegularFile(second) && Files.mismatch(first, second) == -1L;
        } catch (IOException e) {
            return false;
        }
    }

    private static String readMarker(Path marker) {
        try {
            return Files.readString(marker, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static int indexOfAny(String value, char first, char second) {
        int a = value.indexOf(first);
        int b = value.indexOf(second);
        if (a < 0) {
            return b;
        }
        return b < 0 ? a : Math.min(a, b);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    /**
     * @param value Wert
     * @return YAML-Einfachstring; {@code '} wird verdoppelt
     */
    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
