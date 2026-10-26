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
     * @param password Passwort, darf {@code null} sein (wird von der Validierung abgelehnt)
     */
    public RedisSettings {
        address = address == null || address.isBlank() ? DEFAULT_ADDRESS : address.trim();
        password = password == null || password.isBlank() ? null : password;
    }

    @Override
    public String toString() {
        return "RedisSettings[address=" + address + ", password=" + Secrets.redactSecret(password) + "]";
    }
}
