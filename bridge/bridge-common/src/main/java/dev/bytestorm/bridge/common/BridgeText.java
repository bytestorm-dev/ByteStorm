package dev.bytestorm.bridge.common;

/**
 * Feste Texte, die ein Spieler ohne Sprachdatei zu sehen bekommt (§15).
 *
 * <p>Die Bridges haben bewusst keine eigenen Sprachdateien: Sie melden sich an
 * der Cloud an und leiten Kommandos weiter. Sichtbar wird für Spieler nur der
 * Grund, wenn die Cloud jemanden trennt und keinen mitgibt. Damit Proxy und
 * Backend nicht zwei verschiedene Sätze zeigen, steht er hier.
 */
public final class BridgeText {

    /** Grund, wenn die Cloud beim Kick keinen Text mitschickt. */
    public static final String DEFAULT_KICK_REASON = "You were removed from the network";

    private BridgeText() {
    }
}
