package dev.bytestorm.bridge.common;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/**
 * Zugangsdaten und Zeitfenster einer Bridge (§15, §20).
 *
 * <p>Die Werte kommen in dieser Reihenfolge: Systemproperties (für Tests und
 * manuelle Starts), dann die Zugangsdatei aus {@code BYTESTORM_BRIDGE_FILE}, die
 * der Node beim Start in den Service-Ordner schreibt, zuletzt die alten
 * {@code BYTESTORM_*}-Variablen für manuell gestartete Server. Das Passwort wird
 * nie in {@link #toString()} ausgegeben.
 *
 * <p>Die Datei ist der bevorzugte Weg, weil Umgebungsvariablen in
 * {@code /proc/<pid>/environ} und {@code docker inspect} lesbar sind und an
 * Kindprozesse vererbt werden (§20).
 *
 * @param serviceId        Service-UUID
 * @param serviceName      Service-Name, z. B. {@code Lobby-1}
 * @param redisAddress     Redis-Adresse, z. B. {@code redis://localhost:6379}
 * @param redisPassword    Passwort oder {@code null}
 * @param handshakeTimeout Frist für den Handshake
 * @param heartbeatInterval Abstand der Heartbeats; muss unter der Cloud-TTL liegen
 */
public record BridgeSettings(
        UUID serviceId,
        String serviceName,
        String redisAddress,
        String redisPassword,
        Duration handshakeTimeout,
        Duration heartbeatInterval) {

    /** Standardfrist für den Handshake. */
    public static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);

    /** Standardabstand der Heartbeats; die Cloud-TTL liegt bei 30 s. */
    public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(10);

    /** Systemproperty für die Service-ID. */
    public static final String PROPERTY_SERVICE_ID = "bytestorm.service.id";

    /** Systemproperty für den Service-Namen. */
    public static final String PROPERTY_SERVICE_NAME = "bytestorm.service.name";

    /** Systemproperty für die Redis-Adresse. */
    public static final String PROPERTY_REDIS_ADDRESS = "bytestorm.redis.address";

    /** Systemproperty für das Redis-Passwort. */
    public static final String PROPERTY_REDIS_PASSWORD = "bytestorm.redis.password";

    /** Umgebungsvariable für die Service-ID. */
    public static final String ENV_SERVICE_ID = "BYTESTORM_SERVICE_ID";

    /** Umgebungsvariable für den Service-Namen. */
    public static final String ENV_SERVICE_NAME = "BYTESTORM_SERVICE_NAME";

    /** Umgebungsvariable für die Redis-Adresse. */
    public static final String ENV_REDIS_ADDRESS = "BYTESTORM_REDIS_ADDRESS";

    /** Umgebungsvariable für das Redis-Passwort. */
    public static final String ENV_REDIS_PASSWORD = "BYTESTORM_REDIS_PASSWORD";

    /** Umgebungsvariable mit dem Dateinamen der Zugangsdatei (§20). */
    public static final String ENV_BRIDGE_FILE = "BYTESTORM_BRIDGE_FILE";

    /** Dateiname, den der Node in den Service-Ordner schreibt. */
    public static final String FILE_NAME = ".bytestorm-bridge.properties";

    /** Schlüssel in der Zugangsdatei. */
    public static final String KEY_SERVICE_ID = "service.id";

    /** Schlüssel in der Zugangsdatei. */
    public static final String KEY_SERVICE_NAME = "service.name";

    /** Schlüssel in der Zugangsdatei. */
    public static final String KEY_REDIS_ADDRESS = "redis.address";

    /** Schlüssel in der Zugangsdatei. */
    public static final String KEY_REDIS_PASSWORD = "redis.password";

    /**
     * @param serviceId         Service-UUID
     * @param serviceName       Service-Name
     * @param redisAddress      Redis-Adresse
     * @param redisPassword     Passwort oder {@code null}
     * @param handshakeTimeout  Frist für den Handshake
     * @param heartbeatInterval Abstand der Heartbeats
     */
    public BridgeSettings {
        Objects.requireNonNull(serviceId, "serviceId");
        serviceName = requireSimpleName(serviceName);
        redisAddress = requireAddress(redisAddress);
        if (redisPassword != null && redisPassword.isEmpty()) {
            redisPassword = null;
        }
        handshakeTimeout = requirePositive(handshakeTimeout, "handshakeTimeout");
        heartbeatInterval = requirePositive(heartbeatInterval, "heartbeatInterval");
    }

    /**
     * Liest die Werte aus Systemproperties und Umgebung.
     *
     * @return Einstellungen mit Standard-Zeitfenstern
     * @throws BridgeConfigurationException wenn Pflichtwerte fehlen oder ungültig sind
     */
    public static BridgeSettings fromEnvironment() {
        return fromEnvironment(System.getProperties(), System.getenv(), Path.of(""));
    }

    /**
     * @param properties Systemproperties
     * @param environment Umgebungsvariablen
     * @return Einstellungen mit Standard-Zeitfenstern
     * @throws BridgeConfigurationException wenn Pflichtwerte fehlen oder ungültig sind
     */
    public static BridgeSettings fromEnvironment(Properties properties, Map<String, String> environment) {
        return fromEnvironment(properties, environment, Path.of(""));
    }

    /**
     * @param properties       Systemproperties
     * @param environment      Umgebungsvariablen
     * @param workingDirectory Ordner, gegen den ein relativer Dateiname aufgelöst wird
     * @return Einstellungen mit Standard-Zeitfenstern
     * @throws BridgeConfigurationException wenn Pflichtwerte fehlen oder die Datei nicht lesbar ist
     */
    static BridgeSettings fromEnvironment(Properties properties, Map<String, String> environment,
                                          Path workingDirectory) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(workingDirectory, "workingDirectory");

        Properties file = file(environment, workingDirectory);
        String id = value(properties, environment, file,
                PROPERTY_SERVICE_ID, ENV_SERVICE_ID, KEY_SERVICE_ID);
        String name = value(properties, environment, file,
                PROPERTY_SERVICE_NAME, ENV_SERVICE_NAME, KEY_SERVICE_NAME);
        String address = value(properties, environment, file,
                PROPERTY_REDIS_ADDRESS, ENV_REDIS_ADDRESS, KEY_REDIS_ADDRESS);
        String password = value(properties, environment, file,
                PROPERTY_REDIS_PASSWORD, ENV_REDIS_PASSWORD, KEY_REDIS_PASSWORD);

        UUID serviceId;
        try {
            serviceId = UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BridgeConfigurationException("Service-ID fehlt oder ist keine UUID: '" + id + "'");
        }

        try {
            return new BridgeSettings(serviceId, name, address, password,
                    DEFAULT_HANDSHAKE_TIMEOUT, DEFAULT_HEARTBEAT_INTERVAL);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BridgeConfigurationException("Bridge-Konfiguration ungültig: " + e.getMessage());
        }
    }

    private static String value(Properties properties, Map<String, String> environment,
                                Properties file, String property, String env, String key) {
        String fromProperty = properties.getProperty(property);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty.trim();
        }
        String fromEnvironment = environment.get(env);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.trim();
        }
        String fromFile = file.getProperty(key);
        return fromFile == null || fromFile.isBlank() ? null : fromFile.trim();
    }

    /**
     * Liest die Zugangsdatei, sofern der Node ihren Namen mitgegeben hat.
     *
     * @return die Werte aus der Datei, leer wenn keine angegeben ist
     */
    private static Properties file(Map<String, String> environment, Path workingDirectory) {
        String configured = environment.get(ENV_BRIDGE_FILE);
        if (configured == null || configured.isBlank()) {
            return new Properties();
        }
        Path path = Path.of(configured.trim());
        if (!path.isAbsolute()) {
            path = workingDirectory.toAbsolutePath().resolve(path);
        }
        Properties file = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            file.load(reader);
        } catch (IOException e) {
            throw new BridgeConfigurationException("Bridge-Datei nicht lesbar: " + path
                    + " (" + e.getMessage() + ")");
        }
        return file;
    }

    private static String requireSimpleName(String name) {
        Objects.requireNonNull(name, "serviceName");
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.contains("/") || trimmed.contains("\\")
                || trimmed.equals(".") || trimmed.equals("..")) {
            throw new IllegalArgumentException("Ungültiger Service-Name: '" + name + "'");
        }
        return trimmed;
    }

    private static String requireAddress(String address) {
        Objects.requireNonNull(address, "redisAddress");
        String trimmed = address.trim();
        if (!trimmed.startsWith("redis://") && !trimmed.startsWith("rediss://")) {
            throw new IllegalArgumentException("Redis-Adresse muss mit redis:// oder rediss:// beginnen: '" + address + "'");
        }
        return trimmed;
    }

    private static Duration requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " muss positiv sein: " + value);
        }
        return value;
    }

    /**
     * @return Service-ID als String, wie sie im Proto und in Redis steht
     */
    public String serviceIdAsString() {
        return serviceId.toString();
    }

    @Override
    public String toString() {
        return "BridgeSettings[serviceId=" + serviceId
                + ", serviceName=" + serviceName
                + ", redisAddress=" + redisAddress
                + ", redisPassword=" + (redisPassword == null ? "nicht gesetzt" : "***")
                + ", handshakeTimeout=" + handshakeTimeout
                + ", heartbeatInterval=" + heartbeatInterval + "]";
    }
}
