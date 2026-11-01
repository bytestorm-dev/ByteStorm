package dev.bytestorm.core.config;

/**
 * Redis-Verbindung (§21). Das Passwort wird nie im Klartext ausgegeben (§20).
 *
 * @param address  z. B. {@code redis://localhost:6379}
 * @param password Redis-Auth-Passwort
 */
public record RedisSettings(String address, String password) {

    /** Default für lokale Entwicklung. */
    public static final String DEFAULT_ADDRESS = "redis://localhost:6379";

    /**
     * @param address  Adresse, {@code null}/leer nutzt den Default
     * @param password Passwort, darf {@code null} sein (wird von der Validierung abgelehnt,
     *                 solange {@link SecuritySettings#redisAllowsInsecure()} nicht gilt)
     */
    public RedisSettings {
        address = address == null || address.isBlank() ? DEFAULT_ADDRESS : address.trim();
        password = password == null || password.isBlank() ? null : password;
    }

    /**
     * @return der Host der Adresse ohne Schema, Anmeldedaten und Port
     */
    public String host() {
        String rest = address;
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
        }
        int end = rest.length();
        int path = rest.indexOf('/');
        int query = rest.indexOf('?');
        if (path >= 0) {
            end = Math.min(end, path);
        }
        if (query >= 0) {
            end = Math.min(end, query);
        }
        return Hosts.of(rest.substring(0, end));
    }

    @Override
    public String toString() {
        return "RedisSettings[address=" + address + ", password=" + Secrets.redactSecret(password) + "]";
    }
}
