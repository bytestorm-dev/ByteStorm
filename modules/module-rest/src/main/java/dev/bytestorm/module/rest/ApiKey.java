package dev.bytestorm.module.rest;

import dev.bytestorm.api.module.RestScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Ein API-Key mit Namen und Scopes (§11, §20).
 *
 * <p>Der Name steht nur im Log und in Fehlermeldungen — nie der Key selbst. Zwei
 * Keys dürfen nicht denselben Wert haben, sonst wäre nicht entscheidbar, welche
 * Scopes gelten; {@link ApiKeys} lehnt das beim Laden ab.
 *
 * @param name   Name aus der Konfiguration, z. B. {@code monitoring}
 * @param secret Wert, den Clients mitschicken
 * @param scopes erlaubte Scopes, {@code *} für alle
 */
record ApiKey(String name, String secret, Set<String> scopes) {

    /**
     * @param scope geforderte Berechtigung
     * @return {@code true}, wenn der Key sie trägt
     */
    boolean allows(RestScope scope) {
        return scopes.contains(RestScope.ALL)
                || scopes.contains(scope.id())
                || scopes.contains(scope.family() + ".*");
    }

    /**
     * Vergleicht den Key in konstanter Zeit, damit die Antwortzeit nicht verrät,
     * wie viele Zeichen richtig waren.
     *
     * @param presented Wert aus der Anfrage
     * @return {@code true}, wenn beide übereinstimmen
     */
    boolean matches(String presented) {
        return MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @return Name und Scopes für Logzeilen — ohne den Key
     */
    String describe() {
        return name + " [" + new TreeSet<>(scopes).stream().collect(Collectors.joining(" ")) + "]";
    }
}
