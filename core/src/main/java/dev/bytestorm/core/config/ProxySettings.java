package dev.bytestorm.core.config;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Proxy-übergreifende Einstellungen (§14).
 *
 * <p>Das Forwarding-Secret verbindet Velocity und die Backends über
 * <i>Modern Forwarding</i>. Velocity liest es aus der Datei, die
 * {@code velocity.toml} unter {@code forwarding-secret-file} nennt (Default
 * {@code forwarding.secret}), Paper aus {@code config/paper-global.yml} unter
 * {@code proxies.velocity.secret}. Die Cloud hält genau einen Wert für alle
 * Services und gibt ihn als Platzhalter {@code %SECRET%} an die Templates
 * weiter, damit Proxy und Server denselben Schlüssel erhalten (§10).
 *
 * <p>Ein fehlendes Secret wird beim Start einmalig erzeugt und in
 * {@code config/cloud.json} gespeichert; ein vorhandenes wird nie überschrieben,
 * sonst wären laufende Proxies sofort abgekoppelt.
 *
 * @param forwardingSecret gemeinsames Secret; leer, solange noch keins erzeugt wurde
 */
public record ProxySettings(String forwardingSecret) {

    /** Länge eines neu erzeugten Secrets in Bytes. */
    public static final int SECRET_BYTES = 32;

    /**
     * Entfernt umgebende Leerzeichen; {@code null} gilt als „nicht gesetzt“.
     */
    public ProxySettings {
        forwardingSecret = forwardingSecret == null ? "" : forwardingSecret.trim();
    }

    /**
     * @return Einstellungen ohne Secret
     */
    public static ProxySettings defaults() {
        return new ProxySettings("");
    }

    /**
     * @return {@code true}, wenn ein Secret gesetzt ist
     */
    public boolean hasSecret() {
        return !forwardingSecret.isBlank();
    }

    /**
     * Erzeugt ein neues Zufalls-Secret aus {@value #SECRET_BYTES} Bytes in
     * Hex-Schreibweise. Hex ist bewusst gewählt: keine Anführungszeichen, keine
     * Zeilenumbrüche und damit in TOML-, YAML- und Properties-Dateien gleich
     * unkritisch.
     *
     * @return neues Secret
     */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * @param secret neues Secret
     * @return Kopie dieser Einstellungen mit dem übergebenen Secret
     */
    public ProxySettings withSecret(String secret) {
        return new ProxySettings(secret);
    }

    /**
     * Redigiert das Secret, damit es nicht in Logs oder Fehlermeldungen landet (§20).
     *
     * @return Beschreibung ohne Secret
     */
    @Override
    public String toString() {
        return "ProxySettings[forwardingSecret=" + (hasSecret() ? "***" : "<leer>") + "]";
    }
}
