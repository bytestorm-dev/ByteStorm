package dev.bytestorm.api;

import dev.bytestorm.api.module.ModuleContext;

/**
 * Optionales Cloud-Modul, z. B. REST oder Discord (Konzept §16).
 *
 * <p>Der Lebenszyklus wird vom {@code ModuleManager} im Controller gesteuert:
 * {@link #onLoad(ModuleContext)} einmalig beim Laden, danach
 * {@link #onEnable()} / {@link #onDisable()} bei jedem Umschalten.
 */
public interface CloudModule {

    /**
     * @return eindeutiger Modulname, z. B. {@code rest}
     */
    String name();

    /**
     * @return Version des Moduls
     */
    default String version() {
        return "unknown";
    }

    /**
     * Wird einmalig beim Laden aufgerufen. Hier werden Konfiguration und
     * Ressourcen vorbereitet, aber noch keine Dienste gestartet.
     *
     * @param context Zugriff auf API, Datenverzeichnis und Logger
     */
    void onLoad(ModuleContext context);

    /**
     * Startet das Modul (HTTP-Server, Bot-Verbindung, ...).
     */
    void onEnable();

    /**
     * Stoppt das Modul und gibt Ressourcen frei.
     */
    void onDisable();
}
