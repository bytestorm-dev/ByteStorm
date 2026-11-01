package dev.bytestorm.plugins.proxy.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Ändert einzelne Schalter in {@code config.json}, ohne die Datei neu zu
 * schreiben.
 *
 * <p>{@code /cloud tablist off} soll denselben Effekt haben wie eine Hand am
 * Editor — deshalb wird die vorhandene Datei gelesen und nur das betroffene Feld
 * gesetzt. Felder, die diese Version nicht kennt, bleiben erhalten; eine
 * unlesbare Datei wird gemeldet statt überschrieben.
 */
public final class SettingsEditor {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /**
     * Setzt {@code tablist.enabled}.
     *
     * @param file    Konfigurationsdatei
     * @param enabled neuer Zustand
     * @throws IOException wenn die Datei unlesbar ist oder nicht geschrieben werden kann
     */
    public void setTablistEnabled(Path file, boolean enabled) throws IOException {
        ObjectNode root = read(file);
        ObjectNode tablist;
        if (root.get("tablist") instanceof ObjectNode existing) {
            tablist = existing;
        } else {
            tablist = root.putObject("tablist");
        }
        tablist.put("enabled", enabled);
        write(file, root);
    }

    private static ObjectNode read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return JSON.createObjectNode();
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (content.isBlank()) {
            return JSON.createObjectNode();
        }
        JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (IOException e) {
            throw new IOException(file + " ist kein gültiges JSON: " + e.getMessage(), e);
        }
        if (!(root instanceof ObjectNode object)) {
            throw new IOException(file + " muss ein JSON-Objekt sein");
        }
        return object;
    }

    private static void write(Path file, ObjectNode root) throws IOException {
        Path absolute = file.toAbsolutePath();
        Path directory = absolute.getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        // Erst daneben schreiben, dann tauschen: Ein Abbruch hinterlässt keine
        // halbe Datei, die der nächste Tick nicht übernehmen könnte.
        Path temp = Files.createTempFile(directory, "config", ".json.tmp");
        try {
            Files.writeString(temp, JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root) + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
