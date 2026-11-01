package dev.bytestorm.node.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Prüft das Forwarding zwischen Proxy und Backends (§14).
 *
 * <p>Die Cloud schreibt dasselbe Secret als {@code %SECRET%} in
 * {@code templates/PROXY/forwarding.secret} und
 * {@code templates/SERVER/config/paper-global.yml}. Ändert der Betreiber eine der
 * beiden Dateien — oder entsteht ein Service-Ordner noch mit alten Werten —
 * startet alles, aber Paper weist jede Proxy-Verbindung mit
 * <i>"This server requires you to connect with Velocity"</i> ab. Diese Klasse
 * liest die Dateien der Service-Ordner und nennt die Abweichung beim Namen,
 * statt sie erst im Backend-Log sichtbar zu machen.
 *
 * <p>Gelesen wird bewusst ohne TOML-/YAML-Bibliothek: Es geht nur um die wenigen
 * Schlüssel, die ByteStorm selbst schreibt. Fehlt ein Schlüssel, gilt der
 * Standard des jeweiligen Servers.
 */
public final class ForwardingCheck {

    /** Velocity-Konfiguration im Proxy-Ordner. */
    public static final String VELOCITY_CONFIG = "velocity.toml";

    /** Secret-Datei, wenn {@code velocity.toml} keine andere nennt. */
    public static final String DEFAULT_SECRET_FILE = "forwarding.secret";

    /** Paper-Konfiguration im Backend-Ordner. */
    public static final String PAPER_CONFIG = "config/paper-global.yml";

    /**
     * Gewicht einer Abweichung. {@code ERROR} heißt: In dieser Kombination
     * verbindet sich kein Spieler über den Proxy.
     */
    public enum Severity {
        WARNING,
        ERROR
    }

    /**
     * @param severity Schwere
     * @param service  Service, in dessen Ordner die Abweichung liegt
     * @param message  was nicht zusammenpasst
     */
    public record Finding(Severity severity, String service, String message) {
    }

    /**
     * @param mode       {@code player-info-forwarding-mode}
     * @param secretFile Name der Secret-Datei laut {@code forwarding-secret-file}
     * @param secret     Inhalt der Secret-Datei, {@code null} wenn sie fehlt
     */
    public record ProxyConfig(String mode, String secretFile, String secret) {

        /**
         * @return {@code true}, wenn das moderne Forwarding aktiv ist
         */
        public boolean modern() {
            return mode != null && mode.equalsIgnoreCase("modern");
        }
    }

    /**
     * @param enabled    {@code proxies.velocity.enabled}
     * @param onlineMode {@code proxies.velocity.online-mode}
     * @param secret     {@code proxies.velocity.secret}, {@code null} wenn leer
     */
    public record BackendConfig(boolean enabled, boolean onlineMode, String secret) {
    }

    /**
     * @param name    Name des Eintrags in {@code [servers]}
     * @param address Ziel im Format {@code host:port}
     */
    public record ServerEntry(String name, String address) {
    }

    private ForwardingCheck() {
    }

    /**
     * Liest die Forwarding-Einstellungen eines Proxys.
     *
     * @param directory Service-Ordner
     * @return Einstellungen oder leer, wenn dort kein Proxy liegt
     * @throws IOException wenn {@code velocity.toml} nicht gelesen werden kann
     */
    public static Optional<ProxyConfig> readProxy(Path directory) throws IOException {
        Path file = directory.resolve(VELOCITY_CONFIG);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Map<String, String> values = tomlTopLevel(Files.readAllLines(file, StandardCharsets.UTF_8));
        String mode = values.getOrDefault("player-info-forwarding-mode", "none");
        String secretFile = values.getOrDefault("forwarding-secret-file", DEFAULT_SECRET_FILE);
        return Optional.of(new ProxyConfig(mode, secretFile, readSecret(directory, secretFile)));
    }

    /**
     * Liest den Velocity-Abschnitt eines Paper-Backends.
     *
     * @param directory Service-Ordner
     * @return Einstellungen oder leer, wenn dort kein Backend liegt
     * @throws IOException wenn {@code config/paper-global.yml} nicht gelesen werden kann
     */
    public static Optional<BackendConfig> readBackend(Path directory) throws IOException {
        Path file = directory.resolve(PAPER_CONFIG);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Map<String, String> values = yamlValues(Files.readAllLines(file, StandardCharsets.UTF_8));
        return Optional.of(new BackendConfig(
                Boolean.parseBoolean(values.getOrDefault("proxies.velocity.enabled", "false")),
                Boolean.parseBoolean(values.getOrDefault("proxies.velocity.online-mode", "false")),
                blankToNull(values.get("proxies.velocity.secret"))));
    }

    /**
     * Liest die statischen Server-Einträge aus {@code velocity.toml}.
     *
     * <p>Die Bridge schreibt die Services aus der Registry selbst dorthin. Ein
     * Eintrag, der auf dieselbe Adresse wie ein Service zeigt, aber anders heißt,
     * lässt Spieler zwischen zwei Namen hin- und herspringen.
     *
     * @param directory Proxy-Ordner
     * @return Einträge aus {@code [servers]}, leer ohne Datei
     * @throws IOException wenn {@code velocity.toml} nicht gelesen werden kann
     */
    public static List<ServerEntry> readServers(Path directory) throws IOException {
        Path file = directory.resolve(VELOCITY_CONFIG);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<ServerEntry> entries = new ArrayList<>();
        boolean inServers = false;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                inServers = line.equals("[servers]");
                continue;
            }
            if (!inServers) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = unquote(line.substring(0, equals).trim());
            String address = unquote(line.substring(equals + 1).trim());
            // `try = [...]` ist die Reihenfolge, kein Server-Eintrag.
            if (name.isEmpty() || address.isEmpty() || address.startsWith("[")) {
                continue;
            }
            entries.add(new ServerEntry(name, address));
        }
        return List.copyOf(entries);
    }

    /**
     * Vergleicht Proxy- und Backend-Ordner mit dem Secret der Cloud.
     *
     * @param proxies         Proxy-Name zu Service-Ordner
     * @param backends        Backend-Name zu Service-Ordner
     * @param expectedSecret  Secret aus {@code config/cloud.json}, darf leer sein
     * @return alle Abweichungen, leer wenn alles zusammenpasst
     */
    public static List<Finding> compare(Map<String, Path> proxies, Map<String, Path> backends,
                                        String expectedSecret) {
        List<Finding> findings = new ArrayList<>();
        String expected = blankToNull(expectedSecret);
        List<String> proxySecrets = new ArrayList<>();

        for (Map.Entry<String, Path> entry : proxies.entrySet()) {
            String name = entry.getKey();
            ProxyConfig proxy;
            try {
                Optional<ProxyConfig> read = readProxy(entry.getValue());
                if (read.isEmpty()) {
                    findings.add(new Finding(Severity.ERROR, name,
                            VELOCITY_CONFIG + " fehlt in " + entry.getValue()));
                    continue;
                }
                proxy = read.get();
            } catch (IOException e) {
                findings.add(new Finding(Severity.ERROR, name,
                        VELOCITY_CONFIG + " nicht lesbar: " + e.getMessage()));
                continue;
            }
            if (!proxy.modern()) {
                findings.add(new Finding(Severity.ERROR, name,
                        "player-info-forwarding-mode ist '" + proxy.mode()
                                + "' statt 'modern' — Paper-Backends weisen die Verbindung ab"));
            }
            if (proxy.secret() == null) {
                findings.add(new Finding(Severity.ERROR, name,
                        "Secret-Datei '" + proxy.secretFile() + "' fehlt oder ist leer"));
            } else {
                proxySecrets.add(proxy.secret());
                if (expected != null && !expected.equals(proxy.secret())) {
                    findings.add(new Finding(Severity.ERROR, name,
                            "Secret in '" + proxy.secretFile() + "' weicht von config/cloud.json ab"));
                }
            }
        }

        for (Map.Entry<String, Path> entry : backends.entrySet()) {
            String name = entry.getKey();
            BackendConfig backend;
            try {
                Optional<BackendConfig> read = readBackend(entry.getValue());
                if (read.isEmpty()) {
                    findings.add(new Finding(Severity.ERROR, name,
                            PAPER_CONFIG + " fehlt in " + entry.getValue()));
                    continue;
                }
                backend = read.get();
            } catch (IOException e) {
                findings.add(new Finding(Severity.ERROR, name,
                        PAPER_CONFIG + " nicht lesbar: " + e.getMessage()));
                continue;
            }
            if (!backend.enabled()) {
                findings.add(new Finding(Severity.ERROR, name,
                        "proxies.velocity.enabled ist false — der Server nimmt keine Proxy-Spieler an"));
            }
            if (backend.secret() == null) {
                findings.add(new Finding(Severity.ERROR, name,
                        "proxies.velocity.secret fehlt in " + PAPER_CONFIG));
            } else if (expected != null && !expected.equals(backend.secret())) {
                String source = proxySecrets.stream().noneMatch(backend.secret()::equals)
                        ? "Proxy und config/cloud.json" : "config/cloud.json";
                findings.add(new Finding(Severity.ERROR, name,
                        "Secret in " + PAPER_CONFIG + " weicht von " + source + " ab"));
            }
        }
        return List.copyOf(findings);
    }

    private static String readSecret(Path directory, String secretFile) throws IOException {
        Path file = directory.resolve(secretFile).normalize();
        // Der Dateiname kommt aus einer Datei im Service-Ordner: nicht ausbrechen lassen.
        if (!file.startsWith(directory.normalize()) || !Files.isRegularFile(file)) {
            return null;
        }
        return blankToNull(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static Map<String, String> tomlTopLevel(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                // Ab hier folgen Abschnitte; die Forwarding-Keys stehen davor.
                break;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            values.put(line.substring(0, equals).trim(), unquote(line.substring(equals + 1).trim()));
        }
        return values;
    }

    /**
     * Flacht die verschachtelten YAML-Blöcke zu {@code a.b.c}-Pfaden ab. Listen
     * werden übersprungen, weil dort keine Forwarding-Einstellung steht.
     */
    private static Map<String, String> yamlValues(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<Integer, String> sections = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = stripComment(raw);
            if (line.isBlank()) {
                continue;
            }
            int spaces = 0;
            while (spaces < line.length() && line.charAt(spaces) == ' ') {
                spaces++;
            }
            final int indent = spaces;
            String content = line.trim();
            if (content.startsWith("-")) {
                continue;
            }
            int colon = content.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = unquote(content.substring(0, colon).trim());
            String value = content.substring(colon + 1).trim();
            // Ein geringerer Einzug beendet alle tieferen Abschnitte.
            sections.keySet().removeIf(level -> level >= indent);
            String prefix = sections.isEmpty() ? "" : String.join(".", sections.values()) + ".";
            if (value.isEmpty()) {
                sections.put(indent, key);
            } else {
                values.put(prefix + key, unquote(value));
            }
        }
        return values;
    }

    /**
     * Entfernt einen Kommentar, ohne {@code #} innerhalb von Anführungszeichen
     * abzuschneiden (Secrets können Sonderzeichen enthalten).
     */
    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
            } else if (current == '"' || current == '\'') {
                quote = current;
            } else if (current == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
