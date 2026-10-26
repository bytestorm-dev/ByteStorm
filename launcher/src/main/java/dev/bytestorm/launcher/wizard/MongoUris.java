package dev.bytestorm.launcher.wizard;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Baut MongoDB-URIs mit Benutzer und Passwort aus den Wizard-Eingaben (§5.1).
 *
 * <p>Benutzer und Passwort werden prozentkodiert, damit Sonderzeichen wie
 * {@code @}, {@code :} oder {@code /} die URI nicht zerlegen. Eine URI, die
 * bereits Zugangsdaten enthält, bleibt unverändert.
 */
final class MongoUris {

    private MongoUris() {
    }

    /**
     * @param uri      Connection-String, z. B. {@code mongodb://localhost:27017}
     * @param username Benutzername, {@code null}/leer lässt die URI unverändert
     * @param password Passwort, {@code null}/leer fügt nur den Benutzer ein
     * @return URI mit Zugangsdaten
     */
    static String withCredentials(String uri, String username, String password) {
        if (username == null || username.isBlank()) {
            return uri;
        }
        int authorityStart = uri.indexOf("://");
        if (authorityStart < 0) {
            return uri;
        }
        authorityStart += 3;
        int authorityEnd = uri.indexOf('/', authorityStart);
        if (authorityEnd < 0) {
            authorityEnd = uri.length();
        }
        if (uri.substring(authorityStart, authorityEnd).contains("@")) {
            return uri;
        }

        StringBuilder credentials = new StringBuilder(encode(username));
        if (password != null && !password.isEmpty()) {
            credentials.append(':').append(encode(password));
        }
        return uri.substring(0, authorityStart) + credentials + '@' + uri.substring(authorityStart);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
