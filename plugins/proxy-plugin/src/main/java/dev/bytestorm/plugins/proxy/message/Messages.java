package dev.bytestorm.plugins.proxy.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.bytestorm.plugins.proxy.config.ProxySettings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Sprachdateien des Proxy-Plugins (§16).
 *
 * <p>Aufbau: {@code messages/<sprache>.json} im Plugin-Jar, dazu Überschreibungen
 * unter {@code messages/} im Datenverzeichnis des Plugins. Die Schlüssel sind
 * flach ({@code tablist.header}, {@code maintenance.kick}), Platzhalter stehen in
 * geschweiften Klammern ({@code {players}}).
 *
 * <p>Fehlt ein Schlüssel, liefert {@link #text(String, Map)} den Schlüssel selbst
 * zurück: ein sichtbarer Fehler statt einer leeren Zeile. Fehlt eine ganze
 * Sprache, gilt Englisch — der Betreiber merkt das an {@link #language()}.
 */
public final class Messages {

    /** Ressourcenordner der mitgelieferten Sprachen. */
    public static final String RESOURCE_DIRECTORY = "messages";

    private static final String SUFFIX = ".json";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final String language;
    private final Map<String, String> entries;

    private Messages(String language, Map<String, String> entries) {
        this.language = language;
        this.entries = Map.copyOf(entries);
    }

    /**
     * Lädt die Sprache mit Englisch als Rückfallebene.
     *
     * @param language          gewünschter Sprachcode, z. B. {@code de}
     * @param overrideDirectory Ordner mit Überschreibungen, darf {@code null} sein
     * @return die geladenen Texte
     * @throws IOException wenn eine gefundene Datei unlesbar ist oder gar keine Sprache existiert
     */
    public static Messages load(String language, Path overrideDirectory) throws IOException {
        String requested = language == null || language.isBlank()
                ? ProxySettings.DEFAULT_LANGUAGE
                : language.trim();

        Map<String, String> entries = new LinkedHashMap<>(readClasspath(ProxySettings.DEFAULT_LANGUAGE));
        boolean languageFound = requested.equals(ProxySettings.DEFAULT_LANGUAGE);
        if (!languageFound) {
            Map<String, String> translated = readClasspath(requested);
            if (!translated.isEmpty()) {
                entries.putAll(translated);
                languageFound = true;
            }
        }

        Path override = overrideDirectory == null
                ? null
                : overrideDirectory.resolve(requested + SUFFIX);
        if (override != null && Files.isRegularFile(override)) {
            entries.putAll(readFile(override));
            languageFound = true;
        }

        if (entries.isEmpty()) {
            throw new IOException("Keine Sprachdatei gefunden — erwartet unter "
                    + RESOURCE_DIRECTORY + "/" + requested + SUFFIX);
        }
        return new Messages(languageFound ? requested : ProxySettings.DEFAULT_LANGUAGE, entries);
    }

    /**
     * @return die tatsächlich verwendete Sprache; weicht von der gewünschten ab,
     *         wenn keine Sprachdatei existiert
     */
    public String language() {
        return language;
    }

    /**
     * @return alle Schlüssel, alphabetisch — Basis für Vollständigkeitstests
     */
    public Set<String> keys() {
        return Set.copyOf(new TreeSet<>(entries.keySet()));
    }

    /**
     * @param key Schlüssel, z. B. {@code tablist.header}
     * @return der Rohtext ohne Platzhalterersetzung
     */
    public Optional<String> find(String key) {
        return Optional.ofNullable(key == null ? null : entries.get(key));
    }

    /**
     * @param key    Schlüssel
     * @param values Platzhalter
     * @return der Text mit ersetzten Platzhaltern, leer wenn der Schlüssel fehlt
     */
    public Optional<String> find(String key, Map<String, String> values) {
        return find(key).map(template -> replace(template, values));
    }

    /**
     * @param key    Schlüssel
     * @param values Platzhalter
     * @return der Text; fehlt der Schlüssel, der Schlüssel selbst
     */
    public String text(String key, Map<String, String> values) {
        Objects.requireNonNull(key, "key");
        return find(key).map(template -> replace(template, values)).orElse(key);
    }

    /**
     * Ersetzt {@code {name}}-Platzhalter. Unbekannte Platzhalter bleiben stehen,
     * damit ein fehlender Wert in der Anzeige auffällt statt zu verschwinden.
     *
     * @param template Vorlage aus der Sprachdatei
     * @param values   Platzhalter und Werte
     * @return der ersetzte Text
     */
    public static String replace(String template, Map<String, String> values) {
        if (template == null) {
            return "";
        }
        if (values == null || values.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private static Map<String, String> readClasspath(String language) throws IOException {
        String resource = RESOURCE_DIRECTORY + "/" + language + SUFFIX;
        ClassLoader loader = Messages.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) {
                return Map.of();
            }
            return read(stream.readAllBytes(), resource);
        }
    }

    private static Map<String, String> readFile(Path file) throws IOException {
        return read(Files.readAllBytes(file), file.toString());
    }

    private static Map<String, String> read(byte[] content, String source) throws IOException {
        JsonNode root;
        try {
            root = JSON.readTree(new String(content, StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IOException(source + " ist kein gültiges JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IOException(source + " muss ein JSON-Objekt sein");
        }
        Map<String, String> entries = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isTextual()) {
                throw new IOException(source + ": Wert von '" + field.getKey() + "' muss Text sein");
            }
            entries.put(field.getKey(), field.getValue().asText());
        }
        return entries;
    }
}
