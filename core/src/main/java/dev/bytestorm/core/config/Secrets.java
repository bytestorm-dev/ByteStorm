package dev.bytestorm.core.config;

import lombok.experimental.UtilityClass;

/**
 * Redigiert Zugangsdaten, damit sie nicht in Logs oder Exception-Texten landen (§20).
 */
@UtilityClass
final class Secrets {

    private static final String REDACTED = "***";

    /**
     * Entfernt das Passwort aus einer Mongo-URI: {@code mongodb://user:pass@host}
     * wird zu {@code mongodb://user:***@host}.
     *
     * @param uri Mongo-URI, darf {@code null} sein
     * @return redigierte URI
     */
    static String redactMongoUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return "";
        }
        int schemeEnd = uri.indexOf("://");
        int at = uri.indexOf('@', schemeEnd + 3);
        if (schemeEnd < 0 || at < 0) {
            return uri;
        }
        String userInfo = uri.substring(schemeEnd + 3, at);
        int colon = userInfo.indexOf(':');
        if (colon < 0) {
            return uri;
        }
        return uri.substring(0, schemeEnd + 3)
                + userInfo.substring(0, colon + 1)
                + REDACTED
                + uri.substring(at);
    }

    /**
     * @param value Secret, darf {@code null} sein
     * @return {@code ***}, wenn ein Wert gesetzt ist, sonst {@code <leer>}
     */
    static String redactSecret(String value) {
        return value == null || value.isBlank() ? "<leer>" : REDACTED;
    }
}
