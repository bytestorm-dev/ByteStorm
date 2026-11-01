package dev.bytestorm.api;

import dev.bytestorm.api.module.AccessGrant;
import dev.bytestorm.api.module.ModuleContext;

import java.util.List;
import java.util.Map;

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

    /**
     * Beschreibt die Zugänge, die das Modul aus seinen Einstellungen ableitet —
     * für {@code module list}/{@code module info} in der CLI (§16, §20).
     *
     * <p>Die Wahrheit liegt beim Modul: Es kennt seine Schlüsselnamen und weiß,
     * welche Rechte eine fehlende Angabe bedeutet. Die CLI muss dieselben Regeln
     * deshalb nicht noch einmal lesen. Geheimnisse gehören nicht in die Antwort.
     *
     * @param settings Einstellungen des Moduls, wie sie {@link ModuleContext#settings()} liefert
     * @return je Zugang ein Eintrag, leer wenn das Modul keine Rechte kennt oder die
     *         Einstellungen unbrauchbar sind
     */
    default List<AccessGrant> access(Map<String, String> settings) {
        return List.of();
    }
}
