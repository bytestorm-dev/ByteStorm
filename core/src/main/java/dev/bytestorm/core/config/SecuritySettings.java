package dev.bytestorm.core.config;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Sicherheitsentscheidungen der Cloud (§20).
 *
 * <p>MongoDB und Redis tragen den gesamten Zustand der Cloud. Ohne Anmeldung darf
 * nur gestartet werden, wenn der Betreiber das ausdrücklich zusagt — entweder
 * weil der Dienst ausschließlich im privaten Netz lauscht oder weil es eine
 * lokale Testumgebung ist.
 *
 * <p>Die Zusage lässt sich je Datenbank geben:
 *
 * <pre>{@code
 * "security": {
 *   "allowInsecure": false,        // gilt für beide, wenn nichts anderes steht
 *   "allowInsecureMongo": false,   // Vorrang für MongoDB
 *   "allowInsecureRedis": true     // Vorrang für Redis
 * }
 * }</pre>
 *
 * <p>So bleibt MongoDB mit Anmeldung, während Redis lokal ohne Passwort läuft —
 * und umgekehrt. Was gilt, protokolliert der Start.
 *
 * @param allowInsecure     Zusage für beide Datenbanken; nur wenn die
 *                          Einzelwerte fehlen
 * @param allowInsecureMongo Zusage für MongoDB, {@code null} heißt „wie {@code allowInsecure}“
 * @param allowInsecureRedis Zusage für Redis, {@code null} heißt „wie {@code allowInsecure}“
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SecuritySettings(boolean allowInsecure, Boolean allowInsecureMongo,
                               Boolean allowInsecureRedis) {

    /** Standard: Authentifizierung ist Pflicht. */
    public static final boolean DEFAULT_ALLOW_INSECURE = false;

    /**
     * @return die Standardeinstellungen mit erzwungener Authentifizierung
     */
    public static SecuritySettings defaults() {
        return new SecuritySettings(DEFAULT_ALLOW_INSECURE, null, null);
    }

    /**
     * @return {@code true}, wenn MongoDB ohne Anmeldung starten darf
     */
    public boolean mongoAllowsInsecure() {
        return allowInsecureMongo != null ? allowInsecureMongo : allowInsecure;
    }

    /**
     * @return {@code true}, wenn Redis ohne Passwort starten darf
     */
    public boolean redisAllowsInsecure() {
        return allowInsecureRedis != null ? allowInsecureRedis : allowInsecure;
    }

    /**
     * @return {@code true}, wenn mindestens eine Datenbank ohne Anmeldung läuft
     */
    public boolean anyInsecureAllowed() {
        return mongoAllowsInsecure() || redisAllowsInsecure();
    }
}
