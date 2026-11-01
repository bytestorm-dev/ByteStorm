package dev.bytestorm.core.config;

/**
 * MongoDB-Verbindung (§21). Fehlende Werte fallen auf lokale Defaults zurück.
 *
 * @param uri      Connection-String, z. B. {@code mongodb://user:pass@localhost:27017}
 * @param database Name der ByteStorm-Datenbank
 */
public record MongoSettings(String uri, String database) {

    /** Default für lokale Entwicklung. */
    public static final String DEFAULT_URI = "mongodb://localhost:27017";

    /** Default-Datenbankname. */
    public static final String DEFAULT_DATABASE = "bytestorm";

    /**
     * @param uri      Connection-String, {@code null}/leer nutzt den Default
     * @param database Datenbankname, {@code null}/leer nutzt den Default
     */
    public MongoSettings {
        uri = uri == null || uri.isBlank() ? DEFAULT_URI : uri.trim();
        database = database == null || database.isBlank() ? DEFAULT_DATABASE : database.trim();
    }

    /**
     * Prüft, ob die URI Anmeldedaten enthält — {@code mongodb://user:pass@host}.
     *
     * <p>Gezählt wird der Benutzerteil vor dem letzten {@code @} der Autorität;
     * auch eine Anmeldung ohne Passwort (etwa X.509, {@code mongodb://CN=client@host})
     * gilt als authentifiziert. Ohne diesen Teil startet die Cloud nicht, solange
     * {@link SecuritySettings#mongoAllowsInsecure()} nicht ausdrücklich gilt (§20).
     *
     * @return {@code true}, wenn die URI einen Benutzer nennt
     */
    public boolean hasAuthentication() {
        String authority = authority();
        int at = authority.lastIndexOf('@');
        return at > 0 && !authority.substring(0, at).isBlank();
    }

    /**
     * @return der Host der URI ohne Anmeldedaten und Port, etwa {@code localhost}
     */
    public String host() {
        return Hosts.of(authority());
    }

    /**
     * @return der Autoritätsteil der URI (zwischen Schema und Pfad bzw. Query)
     */
    private String authority() {
        int scheme = uri.indexOf("://");
        String rest = scheme < 0 ? uri : uri.substring(scheme + 3);
        int end = rest.length();
        int path = rest.indexOf('/');
        int query = rest.indexOf('?');
        if (path >= 0) {
            end = Math.min(end, path);
        }
        if (query >= 0) {
            end = Math.min(end, query);
        }
        return rest.substring(0, end);
    }

    @Override
    public String toString() {
        return "MongoSettings[uri=" + Secrets.redactMongoUri(uri) + ", database=" + database + "]";
    }
}
