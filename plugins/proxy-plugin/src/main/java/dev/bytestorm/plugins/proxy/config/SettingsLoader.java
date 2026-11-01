package dev.bytestorm.plugins.proxy.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Liest {@code config.json} des Proxy-Plugins (§16).
 *
 * <p>Unbekannte Felder sind erlaubt: Eine neuere Plugin-Version darf
 * Einstellungen hinzufügen, ohne dass eine ältere Konfiguration unlesbar wird.
 * Falsche Typen und unbrauchbare Werte werden dagegen gemeldet — ein stiller
 * Rückfall auf den Default würde einen Tippfehler monatelang verstecken.
 */
public final class SettingsLoader {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * @param file Konfigurationsdatei
     * @return die Einstellungen; fehlt die Datei, gelten die Defaults
     * @throws IOException       wenn die Datei nicht lesbar ist
     * @throws SettingsException wenn Inhalt oder Werte unbrauchbar sind
     */
    public ProxySettings load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return ProxySettings.defaults();
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * @param json Inhalt der Konfigurationsdatei
     * @return die Einstellungen
     * @throws SettingsException wenn Inhalt oder Werte unbrauchbar sind
     */
    public ProxySettings parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new SettingsException("config.json ist kein gültiges JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new SettingsException("config.json muss ein JSON-Objekt sein");
        }
        try {
            return new ProxySettings(
                    text(root, "language", ProxySettings.DEFAULT_LANGUAGE),
                    tablist(root.path("tablist")),
                    maintenance(root.path("maintenance")),
                    cloud(root.path("cloud")));
        } catch (IllegalArgumentException e) {
            throw new SettingsException("config.json enthält einen ungültigen Wert: " + e.getMessage(), e);
        }
    }

    private static ProxySettings.Tablist tablist(JsonNode node) {
        return new ProxySettings.Tablist(
                flag(node, "enabled", ProxySettings.Tablist.defaults().enabled()),
                number(node, "updateSeconds", ProxySettings.Tablist.DEFAULT_UPDATE_SECONDS),
                text(node, "permission", ProxySettings.Tablist.DEFAULT_PERMISSION));
    }

    private static ProxySettings.Maintenance maintenance(JsonNode node) {
        return new ProxySettings.Maintenance(
                flag(node, "enabled", false),
                text(node, "bypassPermission", ProxySettings.Maintenance.DEFAULT_BYPASS_PERMISSION),
                strings(node.path("allowedPlayers")));
    }

    private static ProxySettings.Cloud cloud(JsonNode node) {
        return new ProxySettings.Cloud(
                flag(node, "enabled", false),
                text(node, "baseUrl", ProxySettings.Cloud.DEFAULT_BASE_URL),
                text(node, "apiKey", ""),
                text(node, "permission", ProxySettings.Cloud.DEFAULT_PERMISSION),
                number(node, "timeoutSeconds", ProxySettings.Cloud.DEFAULT_TIMEOUT_SECONDS));
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isTextual()) {
            throw new SettingsException("Feld '" + field + "' muss Text sein");
        }
        return value.asText();
    }

    private static boolean flag(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw new SettingsException("Feld '" + field + "' muss true oder false sein");
        }
        return value.asBoolean();
    }

    private static int number(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isIntegralNumber()) {
            throw new SettingsException("Feld '" + field + "' muss eine ganze Zahl sein");
        }
        return value.asInt();
    }

    private static List<String> strings(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new SettingsException("Feld 'allowedPlayers' muss eine Liste sein");
        }
        List<String> values = new ArrayList<>();
        Iterator<JsonNode> entries = node.elements();
        while (entries.hasNext()) {
            JsonNode entry = entries.next();
            if (!entry.isTextual() || entry.asText().isBlank()) {
                throw new SettingsException("Feld 'allowedPlayers' darf nur Namen enthalten");
            }
            values.add(entry.asText().trim());
        }
        return List.copyOf(values);
    }
}
