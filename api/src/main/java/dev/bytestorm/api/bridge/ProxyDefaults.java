package dev.bytestorm.api.bridge;

/**
 * Vorgaben, die Cloud und Bridge gemeinsam nutzen (§14).
 *
 * <p>Die Cloud schreibt sie in die Standard-Templates, die Bridge wertet sie aus.
 * Beide Seiten greifen auf dieselbe Konstante zu, damit Template und Plugin nicht
 * auseinanderlaufen können.
 */
public final class ProxyDefaults {

    /**
     * Name des Platzhalter-Servers in {@code velocity.toml}.
     *
     * <p>Velocity verweigert den Start, wenn unter {@code [servers]} kein Eintrag
     * steht oder die Verbindungsreihenfolge ({@code try}) leer ist. Ein frisch
     * angelegter Proxy kennt aber noch keinen Service — die registriert die Bridge
     * erst aus der Registry. Deshalb trägt die Cloud diesen Platzhalter ein; die
     * Bridge entfernt ihn direkt nach dem Start und wählt den initialen Server
     * beim Login selbst ({@code PlayerChooseInitialServerEvent}). Statisch
     * konfigurierte Server des Betreibers bleiben unangetastet.
     */
    public static final String PLACEHOLDER_SERVER = "bytestorm-placeholder";

    private ProxyDefaults() {
    }
}
