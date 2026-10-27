package dev.bytestorm.api.module;

import dev.bytestorm.proto.ModuleInfo;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Verwaltung der optionalen Module (Konzept §16, §17).
 */
public interface Modules {

    /**
     * @return alle bekannten Module
     */
    Collection<ModuleInfo> list();

    /**
     * @param name Modulname, z. B. {@code rest}
     * @return Modul-Info, falls vorhanden
     */
    Optional<ModuleInfo> get(String name);

    /**
     * @param name Modulname
     * @return {@code true}, wenn das Modul aktiviert ist
     */
    boolean isEnabled(String name);

    /**
     * Liest die Einstellungen eines Moduls.
     *
     * @param name Modulname
     * @return die Einstellungen, leer wenn keine gesetzt sind
     */
    Map<String, String> settings(String name);

    /**
     * Setzt eine einzelne Einstellung und speichert sie. Sie gilt ab dem
     * nächsten Laden des Moduls (§16): Ein laufendes Modul liest seine
     * Einstellungen in {@code onLoad}, ein Neustart der Cloud oder ein erneutes
     * Aktivieren eines nicht laufenden Moduls übernimmt den neuen Wert.
     *
     * @param name  Modulname
     * @param key   Name der Einstellung, z. B. {@code apiKey}
     * @param value Wert; leer oder {@code null} wird abgelehnt
     */
    void setSetting(String name, String key, String value);

    /**
     * Aktiviert ein Modul zur Laufzeit.
     *
     * @param name Modulname
     * @return Future, das nach {@code onEnable} abgeschlossen ist
     */
    CompletableFuture<Void> enable(String name);

    /**
     * Deaktiviert ein Modul zur Laufzeit.
     *
     * @param name Modulname
     * @return Future, das nach {@code onDisable} abgeschlossen ist
     */
    CompletableFuture<Void> disable(String name);
}
