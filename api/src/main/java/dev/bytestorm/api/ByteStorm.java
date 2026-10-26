package dev.bytestorm.api;

import java.util.Objects;

/**
 * Einstiegspunkt der öffentlichen API (Konzept §15).
 *
 * <p>Die Implementierung wird beim Start installiert — in der Cloud vom
 * {@code launcher}, in einem Service vom Bridge-Plugin:
 *
 * <pre>{@code
 * ByteStorm.api().services().byGroup("Lobby");
 * ByteStorm.api().players().connect(uuid, "Lobby");
 * }</pre>
 *
 * <p>Die Klasse hält keinen eigenen Zustand außer der aktuell installierten
 * Implementierung und ist damit pro Prozess eindeutig.
 */
public final class ByteStorm {

    private static volatile ByteStormApi installed;

    private ByteStorm() {
    }

    /**
     * Liefert die installierte API.
     *
     * @return die aktive API-Implementierung
     * @throws IllegalStateException wenn keine Implementierung installiert ist
     */
    public static ByteStormApi api() {
        ByteStormApi current = installed;
        if (current == null) {
            throw new IllegalStateException(
                    "ByteStorm-API ist nicht installiert (läuft dieser Code außerhalb der Cloud oder eines Bridges?)");
        }
        return current;
    }

    /**
     * Installiert die API-Implementierung. Wird von Cloud und Bridge-Plugins
     * beim Enable aufgerufen.
     *
     * @param implementation Implementierung
     * @throws IllegalStateException wenn bereits eine andere Implementierung installiert ist
     */
    public static void install(ByteStormApi implementation) {
        Objects.requireNonNull(implementation, "implementation");
        synchronized (ByteStorm.class) {
            if (installed != null && installed != implementation) {
                throw new IllegalStateException("ByteStorm-API ist bereits installiert: " + installed.getClass().getName());
            }
            installed = implementation;
        }
    }

    /**
     * Entfernt die installierte Implementierung, z. B. beim Disable des Bridges.
     */
    public static void uninstall() {
        synchronized (ByteStorm.class) {
            installed = null;
        }
    }

    /**
     * @return {@code true}, wenn eine Implementierung installiert ist
     */
    public static boolean isInstalled() {
        return installed != null;
    }
}
