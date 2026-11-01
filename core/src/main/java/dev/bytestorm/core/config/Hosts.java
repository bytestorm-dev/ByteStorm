package dev.bytestorm.core.config;

/**
 * Liest den Host aus dem Autoritätsteil einer Verbindungsadresse (§20, §21).
 *
 * <p>Die Cloud muss für den Sicherheitscheck wissen, <em>wo</em> MongoDB und Redis
 * liegen: nur lokal, im privaten Netz oder über eine öffentliche Adresse. Die
 * Adressen der beiden Dienste sind unterschiedlich aufgebaut
 * ({@code redis://…}, {@code mongodb+srv://…}, mit oder ohne Anmeldedaten), der
 * letzte Schritt ist aber derselbe.
 */
final class Hosts {

    private Hosts() {
    }

    /**
     * @param authority Teil nach dem Schema, z. B. {@code user:pass@host:27017}
     * @return der Host ohne Anmeldedaten, Port und IPv6-Klammern; leer wenn keiner erkennbar ist
     */
    static String of(String authority) {
        String value = authority == null ? "" : authority.trim();
        int at = value.lastIndexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        // MongoDB erlaubt mehrere Hosts; der erste bestimmt die Erreichbarkeit.
        int comma = value.indexOf(',');
        if (comma >= 0) {
            value = value.substring(0, comma);
        }
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            return close > 0 ? value.substring(1, close) : value;
        }
        int colon = value.lastIndexOf(':');
        return colon > 0 ? value.substring(0, colon) : value;
    }
}
