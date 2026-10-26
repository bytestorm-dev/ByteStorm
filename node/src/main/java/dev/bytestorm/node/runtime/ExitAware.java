package dev.bytestorm.node.runtime;

import dev.bytestorm.api.service.ServiceHandle;

import java.util.concurrent.CompletableFuture;

/**
 * Runtime, die den Exit ihres Prozesses/Containers melden kann (§12.5).
 *
 * <p>Der Exit-Code ist bei Containern nicht immer verfügbar; Docker liefert
 * dann {@code -1}. Das Future wird genau einmal abgeschlossen, auch wenn der
 * Service vorher gestoppt oder gekillt wurde.
 */
public interface ExitAware {

    /**
     * @param handle laufender Service
     * @return Exit-Code, sobald der Prozess/Container beendet ist
     */
    CompletableFuture<Integer> onExit(ServiceHandle handle);
}
