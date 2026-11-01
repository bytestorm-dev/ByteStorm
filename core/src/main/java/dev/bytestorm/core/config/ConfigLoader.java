package dev.bytestorm.core.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lädt und validiert {@code config/cloud.json} (§21).
 *
 * <p>Unbekannte Felder werden ignoriert, damit eine ältere Cloud eine neuere
 * Konfiguration lesen kann. Fehler werden mit Feldpfad gemeldet.
 */
public final class ConfigLoader {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    /**
     * @param file Pfad zu {@code cloud.json}
     * @return die validierte Konfiguration
     * @throws ConfigException wenn die Datei fehlt, ungültiges JSON enthält oder die Validierung fehlschlägt
     */
    public CloudConfig load(Path file) throws ConfigException {
        String json;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            json = readAll(reader);
        } catch (NoSuchFileException e) {
            throw new ConfigException("Konfigurationsdatei fehlt: " + file.toAbsolutePath(), e);
        } catch (IOException e) {
            throw new ConfigException("Konfigurationsdatei nicht lesbar: " + file.toAbsolutePath(), e);
        }
        return parse(json);
    }

    /**
     * @param json JSON-Inhalt
     * @return die validierte Konfiguration
     * @throws ConfigException wenn das JSON ungültig ist oder die Validierung fehlschlägt
     */
    public CloudConfig parse(String json) throws ConfigException {
        CloudConfig config;
        try {
            config = MAPPER.readValue(json, CloudConfig.class);
        } catch (JsonProcessingException e) {
            throw new ConfigException("Ungültiges JSON in cloud.json: " + e.getOriginalMessage(), e);
        }
        if (config == null) {
            config = CloudConfig.defaults();
        }
        validate(config);
        return config;
    }

    /**
     * Prüft die Konfiguration auf offensichtliche Fehler (§20, §21).
     *
     * @param config zu prüfende Konfiguration
     * @throws ConfigException mit allen gefundenen Problemen
     */
    public void validate(CloudConfig config) throws ConfigException {
        List<String> problems = new ArrayList<>();

        String uri = config.mongo().uri();
        if (!uri.startsWith("mongodb://") && !uri.startsWith("mongodb+srv://")) {
            problems.add("mongo.uri muss mit mongodb:// oder mongodb+srv:// beginnen");
        }
        if (config.mongo().database().isBlank()) {
            problems.add("mongo.database darf nicht leer sein");
        }
        // Ohne Anmeldung läge der gesamte Zustand offen: nur starten, wenn der
        // Betreiber das ausdrücklich zugesagt hat (§20).
        if (!config.mongo().hasAuthentication() && !config.security().mongoAllowsInsecure()) {
            problems.add("mongo.uri braucht einen Benutzer, z. B. mongodb://user:pass@host:27017 —"
                    + " ohne Anmeldung nur mit security.allowInsecureMongo=true (nicht empfohlen)");
        }

        String address = config.redis().address();
        if (!address.startsWith("redis://") && !address.startsWith("rediss://")) {
            problems.add("redis.address muss mit redis:// oder rediss:// beginnen");
        }
        if (config.redis().password() == null && !config.security().redisAllowsInsecure()) {
            problems.add("redis.password ist Pflicht (Redis-Auth, §20) —"
                    + " ohne Passwort nur mit security.allowInsecureRedis=true (nicht empfohlen)");
        }

        if (config.runtime() == dev.bytestorm.proto.RuntimeType.DOCKER && config.docker().host().isBlank()) {
            problems.add("docker.host ist Pflicht, wenn runtime = DOCKER");
        }

        int proxy = config.ports().proxy();
        int serverStart = config.ports().serverStart();
        if (proxy < 1 || proxy > 65535) {
            problems.add("ports.proxy muss zwischen 1 und 65535 liegen");
        }
        if (serverStart < 1 || serverStart > 65535) {
            problems.add("ports.serverStart muss zwischen 1 und 65535 liegen");
        }
        if (proxy == serverStart) {
            problems.add("ports.proxy und ports.serverStart dürfen nicht identisch sein");
        }

        if (config.scaling().intervalSeconds() <= 0) {
            problems.add("scaling.intervalSeconds muss positiv sein");
        }

        if (config.crash().maxRestarts() < 0) {
            problems.add("crash.maxRestarts darf nicht negativ sein");
        }
        if (config.crash().maxRestarts() > 0 && config.crash().backoffSeconds().isEmpty()) {
            problems.add("crash.backoffSeconds darf bei aktivem Auto-Restart nicht leer sein");
        }
        for (int seconds : config.crash().backoffSeconds()) {
            if (seconds < 0) {
                problems.add("crash.backoffSeconds darf keine negativen Werte enthalten");
                break;
            }
        }

        if (!problems.isEmpty()) {
            throw new ConfigException("Ungültige cloud.json:" + System.lineSeparator()
                    + String.join(System.lineSeparator(), problems.stream().map(p -> " - " + p).toList()));
        }
    }

    private static String readAll(Reader reader) throws IOException {
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            builder.append(buffer, 0, read);
        }
        return builder.toString();
    }
}
