package dev.bytestorm.core.rpc;

import dev.bytestorm.proto.CommandResult;
import dev.bytestorm.proto.ExecuteCommand;
import dev.bytestorm.proto.StartServiceRequest;
import dev.bytestorm.proto.StartServiceResponse;
import dev.bytestorm.proto.StopServiceRequest;

/**
 * RPC-Vertrag zwischen Controller und Node (§7, §12).
 *
 * <p>Die Methoden sind bewusst synchron: der Aufrufer bekommt innerhalb des in
 * {@link RpcService#client(Class, java.time.Duration)} gesetzten Timeouts eine
 * Antwort oder eine {@code RemoteServiceTimeoutException} — kein hängendes Future.
 */
public interface CloudRpc {

    /**
     * Startet einen Service der angegebenen Gruppe.
     *
     * @param request Gruppe und Properties
     * @return Ergebnis mit dem gestarteten Service oder einer Fehlermeldung
     */
    StartServiceResponse startService(StartServiceRequest request);

    /**
     * Stoppt einen Service.
     *
     * @param request Service-ID und Force-Flag
     */
    void stopService(StopServiceRequest request);

    /**
     * Führt einen Konsolenbefehl in einem Service aus.
     *
     * @param request Service-ID und Befehl
     * @return Ergebnis des Bridges
     */
    CommandResult executeCommand(ExecuteCommand request);
}
