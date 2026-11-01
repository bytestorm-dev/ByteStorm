package dev.bytestorm.launcher.doctor;

import dev.bytestorm.api.module.AccessGrant;
import dev.bytestorm.api.module.Modules;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Was das REST-Modul über seine Erreichbarkeit verrät (§11, §20): Adresse,
 * Schaltzustand und die Namen der Keys — nie deren Werte.
 *
 * <p>Der {@code Doctor} liegt im Launcher und kennt das Modul nicht: Es liegt als
 * Jar in {@code lib/} und wird über den {@code ServiceLoader} geladen. Deshalb
 * reicht der Launcher diese Sicht hinein; die Einstellungsnamen ({@code host},
 * {@code port}) gleicht {@link #of(Modules)} mit den Voreinstellungen von
 * {@code RestSettings} ab.
 *
 * @param enabled {@code true}, wenn das Modul aktiviert ist
 * @param host    Adresse, auf der gelauscht wird
 * @param port    Port, auf dem gelauscht wird
 * @param grants  Zugänge des Moduls, je Key ein Eintrag ohne Geheimnis
 */
public record RestExposure(boolean enabled, String host, int port, List<AccessGrant> grants) {

    /** Wie {@code RestSettings}: nur lokal. */
    public static final String DEFAULT_HOST = "127.0.0.1";

    /** Wie {@code RestSettings}. */
    public static final int DEFAULT_PORT = 8080;

    /**
     * @param enabled Schaltzustand
     * @param host    Adresse; leer bedeutet {@link #DEFAULT_HOST}
     * @param port    Port
     * @param grants  Zugänge, {@code null} gilt als leer
     */
    public RestExposure {
        host = host == null || host.isBlank() ? DEFAULT_HOST : host.trim();
        grants = grants == null ? List.of() : List.copyOf(grants);
    }

    /**
     * @param modules Modul-Verwaltung der laufenden Cloud
     * @return die Sicht auf das REST-Modul
     */
    public static RestExposure of(Modules modules) {
        Map<String, String> settings = modules.settings("rest");
        return new RestExposure(modules.isEnabled("rest"),
                settings.get("host"), port(settings), List.copyOf(modules.access("rest")));
    }

    /**
     * @return Adresse und Port, z. B. {@code 0.0.0.0:8080}
     */
    public String endpoint() {
        return host + ":" + port;
    }

    /**
     * @return {@code true}, wenn nur der eigene Rechner die API erreicht
     */
    public boolean loopback() {
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals("localhost") || value.equals("::1") || value.equals("[::1]")
                || value.startsWith("127.");
    }

    /**
     * @return {@code true}, wenn auf allen Netzwerkkarten gelauscht wird
     */
    public boolean allInterfaces() {
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals("0.0.0.0") || value.equals("::") || value.equals("[::]")
                || value.equals("*");
    }

    /**
     * @return Namen der Keys, die alles dürfen ({@code *})
     */
    public List<String> adminKeys() {
        return grants.stream().filter(AccessGrant::admin).map(AccessGrant::name).toList();
    }

    private static int port(Map<String, String> settings) {
        String raw = settings.get("port");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            return port >= 1 && port <= 65_535 ? port : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            // Ein unbrauchbarer Wert verhindert den Start des Moduls; für die
            // Diagnose zählt hier nur, dass die Voreinstellung gemeint war.
            return DEFAULT_PORT;
        }
    }
}
