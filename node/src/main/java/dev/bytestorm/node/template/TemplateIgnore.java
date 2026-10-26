package dev.bytestorm.node.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * Filtert Dateien eines Templates über {@code .bytestorm-ignore} (§10).
 *
 * <p>Je Zeile ein Glob, relative zum Template-Ordner. Leere Zeilen und
 * {@code #}-Kommentare werden ignoriert. Ein Muster ohne {@code /} gilt auch
 * für den Dateinamen, ein Muster mit abschließendem {@code /} für den ganzen
 * Ordner.
 */
final class TemplateIgnore {

    private static final String FILE = ".bytestorm-ignore";

    private final List<Rule> rules;

    private TemplateIgnore(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * @param template Template-Ordner
     * @return die Regeln des Ordners, leer ohne Ignore-Datei
     * @throws IOException wenn die Datei nicht gelesen werden kann
     */
    static TemplateIgnore read(Path template) throws IOException {
        Path file = template.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return new TemplateIgnore(List.of());
        }
        List<Rule> rules = new ArrayList<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            rules.add(Rule.of(line));
        }
        return new TemplateIgnore(rules);
    }

    /**
     * @param relative Pfad relativ zum Template-Ordner
     * @return {@code true}, wenn die Datei übersprungen wird
     */
    boolean matches(Path relative) {
        String path = relative.toString().replace('\\', '/');
        for (Rule rule : rules) {
            if (rule.matches(path, relative.getFileName())) {
                return true;
            }
        }
        return false;
    }

    private record Rule(PathMatcher pathMatcher, PathMatcher nameMatcher, String prefix) {

        static Rule of(String pattern) {
            if (pattern.endsWith("/")) {
                String directory = pattern.substring(0, pattern.length() - 1);
                return new Rule(null, null, directory);
            }
            if (pattern.contains("*") || pattern.contains("?")
                    || pattern.contains("[") || pattern.contains("{")) {
                PathMatcher path = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                PathMatcher name = pattern.contains("/")
                        ? null
                        : FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                return new Rule(path, name, null);
            }
            return new Rule(null, null, pattern);
        }

        boolean matches(String path, Path fileName) {
            if (prefix != null) {
                return path.equals(prefix) || path.startsWith(prefix + "/");
            }
            if (pathMatcher != null && pathMatcher.matches(Path.of(path))) {
                return true;
            }
            return nameMatcher != null && nameMatcher.matches(fileName);
        }
    }
}
