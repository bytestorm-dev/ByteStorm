package dev.bytestorm.api.service;

import dev.bytestorm.proto.ServiceType;

import java.util.Locale;
import java.util.Set;

/**
 * Ordnet Plattformnamen der Rolle im Netzwerkverbund zu.
 *
 * <p>{@code velocity} und {@code bungeecord} sind Proxies, alles andere — etwa
 * {@code paper}, {@code purpur} oder {@code folia} — ist ein Backend. Die
 * Unterscheidung entscheidet, welches Template-Ebene ein Service bekommt, aus
 * welchem Port-Pool er startet und welche Forwarding-Datei zu ihm gehört (§14).
 * Deshalb braucht sie genau eine Stelle, die CLI, Controller und Diagnose teilen.
 */
public final class Platforms {

    /** Projektnamen, die einen Proxy bezeichnen. */
    public static final Set<String> PROXY = Set.of(
            "velocity", "bungeecord", "bungee", "waterfall", "travertine");

    private Platforms() {
    }

    /**
     * @param platform Projektname, z. B. {@code velocity}; {@code null} gilt als Backend
     * @return {@code true}, wenn die Plattform ein Proxy ist
     */
    public static boolean isProxy(String platform) {
        return platform != null && PROXY.contains(platform.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * @param platform Projektname, z. B. {@code paper}
     * @return die passende Rolle
     */
    public static ServiceType typeOf(String platform) {
        return isProxy(platform) ? ServiceType.PROXY : ServiceType.SERVER;
    }
}
