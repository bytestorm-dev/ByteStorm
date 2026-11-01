package dev.bytestorm.module.rest;

import dev.bytestorm.api.module.AccessGrant;
import dev.bytestorm.api.module.RestScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Die konfigurierten API-Keys des REST-Moduls (§11, §20).
 *
 * <p>Aufbau der Modul-Konfiguration (flache Schlüssel/Wert-Paare):
 *
 * <pre>{@code
 * apiKey            = <wert>            // voller Zugriff, für Betreiber und CLI
 * apiKey.monitoring = <wert>            // ein Key je Integration
 * scopes.monitoring = monitoring.read, services.read
 * }</pre>
 *
 * <p>Ein Key ohne {@code scopes.<name>} bekommt nur die Lese-Scopes
 * ({@link RestScope#readOnly()}) — wer Rechte braucht, muss sie hinschreiben.
 * Unbekannte Scopes, ein {@code scopes.<name>} ohne zugehörigen Key und zwei
 * Keys mit demselben Wert lehnt {@link #from(Map)} ab, statt sie stillschweigend
 * zu übernehmen.
 */
final class ApiKeys {

    /** Einstellung des unbeschränkten Keys. */
    static final String ADMIN = "apiKey";

    /** Präfix für weitere Keys: {@code apiKey.<name>}. */
    static final String KEY_PREFIX = ADMIN + ".";

    /** Präfix für die Scopes eines Keys: {@code scopes.<name>}. */
    static final String SCOPES_PREFIX = "scopes.";

    private final List<ApiKey> keys;

    private ApiKeys(List<ApiKey> keys) {
        this.keys = List.copyOf(keys);
    }

    /**
     * @param settings Modul-Konfiguration
     * @return die geprüften Keys, mindestens einer
     * @throws IllegalArgumentException wenn kein Key vorhanden oder ein Wert unbrauchbar ist
     */
    static ApiKeys from(Map<String, String> settings) {
        Objects.requireNonNull(settings, "settings");
        Map<String, ApiKey> keys = new LinkedHashMap<>();

        String admin = value(settings, ADMIN, "");
        if (!admin.isEmpty()) {
            requireLength(ADMIN, admin);
            keys.put(ADMIN, new ApiKey(ADMIN, admin, Set.of(RestScope.ALL)));
        }
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String setting = entry.getKey();
            if (!setting.startsWith(KEY_PREFIX)) {
                continue;
            }
            String name = setting.substring(KEY_PREFIX.length()).trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Einstellung '" + setting
                        + "' braucht einen Namen nach '" + KEY_PREFIX + "', z. B. 'apiKey.monitoring'");
            }
            if (keys.containsKey(name)) {
                throw new IllegalArgumentException("Einstellung '" + setting
                        + "' doppelt den Key '" + name + "' — Namen müssen eindeutig sein");
            }
            String secret = entry.getValue() == null ? "" : entry.getValue().trim();
            requireLength(setting, secret);
            keys.put(name, new ApiKey(name, secret, scopes(settings, name)));
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("Kein API-Key konfiguriert — 'apiKey' (voller Zugriff)"
                    + " oder 'apiKey.<name>' mit passenden 'scopes.<name>' setzen");
        }
        for (String setting : settings.keySet()) {
            if (!setting.startsWith(SCOPES_PREFIX)) {
                continue;
            }
            String name = setting.substring(SCOPES_PREFIX.length()).trim();
            if (!keys.containsKey(name)) {
                throw new IllegalArgumentException("Einstellung '" + setting
                        + "' gehört zu keinem Key — 'apiKey." + name + "' fehlt");
            }
        }
        requireDistinctSecrets(keys);
        return new ApiKeys(new ArrayList<>(keys.values()));
    }

    /**
     * Sucht den Key zu einem mitgeschickten Wert. Alle Keys werden verglichen,
     * damit die Laufzeit nicht verrät, an welcher Stelle ein Treffer stand.
     *
     * @param presented Wert aus der Anfrage
     * @return der Key, leer wenn er unbekannt ist
     */
    Optional<ApiKey> resolve(String presented) {
        ApiKey found = null;
        for (ApiKey key : keys) {
            if (key.matches(presented) && found == null) {
                found = key;
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * @return je Key ein Eintrag für Anzeigen wie {@code module info} — ohne die Werte
     */
    List<AccessGrant> grants() {
        return keys.stream()
                .map(key -> new AccessGrant(key.name(), key.scopes().contains(RestScope.ALL),
                        new ArrayList<>(new TreeSet<>(key.scopes()))))
                .toList();
    }

    /**
     * @return Name und Scopes aller Keys für die Startmeldung — ohne die Werte
     */
    String describe() {
        return keys.stream().map(ApiKey::describe).collect(Collectors.joining(", "));
    }

    /**
     * @return Anzahl der konfigurierten Keys
     */
    int size() {
        return keys.size();
    }

    private static Set<String> scopes(Map<String, String> settings, String name) {
        String raw = value(settings, SCOPES_PREFIX + name, "");
        if (raw.isEmpty()) {
            return RestScope.readOnly().stream().map(RestScope::id).collect(Collectors.toSet());
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String token : raw.split("[,\\s]+")) {
            if (!token.isEmpty()) {
                scopes.add(validate(token, SCOPES_PREFIX + name));
            }
        }
        if (scopes.isEmpty()) {
            return RestScope.readOnly().stream().map(RestScope::id).collect(Collectors.toSet());
        }
        return Set.copyOf(scopes);
    }

    /**
     * @param token   Scope aus der Konfiguration
     * @param setting Einstellungsname für die Fehlermeldung
     * @return der geprüfte Scope
     * @throws IllegalArgumentException wenn der Name unbekannt ist
     */
    private static String validate(String token, String setting) {
        if (token.equals(RestScope.ALL)) {
            return token;
        }
        if (token.endsWith(".*")) {
            String family = token.substring(0, token.length() - 2);
            if (RestScope.ids().stream().anyMatch(id -> id.startsWith(family + "."))) {
                return token;
            }
        } else if (RestScope.of(token).isPresent()) {
            return token;
        }
        throw new IllegalArgumentException("Einstellung '" + setting + "' nennt den unbekannten Scope '"
                + token + "' — erlaubt sind " + RestScope.ALL + ", <familie>.* oder "
                + String.join(", ", RestScope.ids()));
    }

    private static void requireLength(String setting, String secret) {
        if (secret.length() < RestSettings.MIN_KEY_LENGTH) {
            throw new IllegalArgumentException("Einstellung '" + setting + "' ist kürzer als "
                    + RestSettings.MIN_KEY_LENGTH + " Zeichen — einen längeren API-Key setzen");
        }
    }

    /**
     * @param keys geprüfte Keys
     * @throws IllegalArgumentException wenn zwei Einstellungen denselben Wert tragen
     */
    private static void requireDistinctSecrets(Map<String, ApiKey> keys) {
        List<ApiKey> all = new ArrayList<>(keys.values());
        for (int first = 0; first < all.size(); first++) {
            for (int second = first + 1; second < all.size(); second++) {
                ApiKey a = all.get(first);
                ApiKey b = all.get(second);
                if (a.secret().equals(b.secret())) {
                    throw new IllegalArgumentException("Einstellungen '" + settingName(a.name())
                            + "' und '" + settingName(b.name()) + "' tragen denselben Key —"
                            + " jeder Key braucht einen eigenen Wert");
                }
            }
        }
    }

    private static String settingName(String name) {
        return name.equals(ADMIN) ? ADMIN : KEY_PREFIX + name;
    }

    private static String value(Map<String, String> settings, String key, String fallback) {
        String value = settings.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
