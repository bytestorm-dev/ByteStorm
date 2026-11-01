package dev.bytestorm.api.module;

import java.util.List;
import java.util.Objects;

/**
 * Ein Zugang zu einem Modul und seine Rechte (§11, §16, §20).
 *
 * <p>Das REST-Modul meldet je API-Key einen Eintrag, damit die CLI einem
 * Betreiber zeigen kann, welcher Key was darf — ohne dass ein Geheimnis
 * irgendwo auftaucht. Module ohne eigene Rechte liefern eine leere Liste.
 *
 * @param name   Name des Zugangs, z. B. {@code monitoring} oder {@code apiKey}
 * @param admin  {@code true}, wenn der Zugang alles darf
 * @param scopes vergebene Scopes, Wildcards wie {@code services.*} bleiben stehen
 */
public record AccessGrant(String name, boolean admin, List<String> scopes) {

    /**
     * @param name   Name des Zugangs
     * @param admin  darf alles
     * @param scopes vergebene Scopes
     */
    public AccessGrant {
        Objects.requireNonNull(name, "name");
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    /**
     * @return die Rechte als Text, z. B. {@code services.read monitoring.read}
     */
    public String scopeText() {
        if (admin) {
            return RestScope.ALL;
        }
        return scopes.isEmpty() ? "—" : String.join(", ", scopes);
    }
}
