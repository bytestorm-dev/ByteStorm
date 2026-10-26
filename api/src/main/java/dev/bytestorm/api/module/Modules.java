package dev.bytestorm.api.module;

import dev.bytestorm.proto.ModuleInfo;

import java.util.Collection;
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
