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

    @Override
    public String toString() {
        return "MongoSettings[uri=" + Secrets.redactMongoUri(uri) + ", database=" + database + "]";
    }
}
