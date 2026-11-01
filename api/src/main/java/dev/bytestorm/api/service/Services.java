package dev.bytestorm.api.service;

import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.StartServiceResponse;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Zugriff auf Services. Lesende Aufrufe kommen aus dem lokalen Cache des
 * Live-States, schreibende laufen asynchron über die Cloud (Konzept §15).
 */
public interface Services {

    /**
     * @return alle bekannten Services, unabhängig vom Zustand
     */
    Collection<ServiceInfo> all();

    /**
     * @param group Gruppenname, z. B. {@code Lobby}
     * @return alle Services dieser Gruppe, leer wenn die Gruppe keine hat
     */
    List<ServiceInfo> byGroup(String group);

    /**
     * @param name Service-Name nach Schema {@code <Gruppe>-<Nummer>}
     * @return der Service, falls vorhanden
     */
    Optional<ServiceInfo> byName(String name);

    /**
     * @param serviceId Service-UUID
     * @return der Service, falls vorhanden
     */
    Optional<ServiceInfo> byId(String serviceId);

    /**
     * @param serviceId Service-UUID
     * @return der Service, falls vorhanden
     */
    default Optional<ServiceInfo> byId(ServiceId serviceId) {
        return byId(serviceId.toString());
    }

    /**
     * Startet einen Service der Gruppe mit Standard-Properties.
     *
     * @param group Gruppenname
     * @return Ergebnis mit dem gestarteten Service oder einer Fehlermeldung
     */
    default CompletableFuture<StartServiceResponse> start(String group) {
        return start(group, Map.of());
    }

    /**
     * Startet einen Service der Gruppe mit zusätzlichen Properties.
     *
     * @param group      Gruppenname
     * @param properties zusätzliche Properties, z. B. {@code template}
     * @return Ergebnis mit dem gestarteten Service oder einer Fehlermeldung
     */
    CompletableFuture<StartServiceResponse> start(String group, Map<String, String> properties);

    /**
     * Fährt einen Service graceful herunter.
     *
     * @param serviceId Service-UUID
     * @return Future, das nach dem Stop abgeschlossen ist
     */
    default CompletableFuture<Void> stop(String serviceId) {
        return stop(serviceId, false);
    }

    /**
     * Fährt einen Service herunter.
     *
     * @param serviceId Service-UUID
     * @param force     {@code true} überspringt den Graceful-Timeout und killt
     * @return Future, das nach dem Stop abgeschlossen ist
     */
    CompletableFuture<Void> stop(String serviceId, boolean force);

    /**
     * Startet einen Service neu (Stop, dann Start in derselben Gruppe).
     *
     * @param serviceId Service-UUID
     * @return Future, das abgeschlossen ist, sobald der Neustart angestoßen wurde
     */
    CompletableFuture<Void> restart(String serviceId);

    /**
     * Sendet einen Konsolenbefehl an einen laufenden Service.
     *
     * @param serviceId Service-UUID
     * @param command   Befehl ohne Zeilenende
     * @return Future mit der Antwort des Bridges
     */
    CompletableFuture<Void> execute(String serviceId, String command);

    /**
     * Wendet die Templates eines Services erneut an (§10).
     *
     * @param serviceId Service-UUID
     * @return Future, das nach dem Anwenden abgeschlossen ist
     */
    CompletableFuture<Void> reapply(String serviceId);

    /**
     * Meldet einen Service als {@code ONLINE} (§14).
     *
     * @param serviceId Service-UUID
     * @return Future, das nach dem Vermerken abgeschlossen ist
     */
    CompletableFuture<Void> online(String serviceId);

    /**
     * @param serviceId Service-UUID
     * @return die letzten Logzeilen des Services, leer wenn keine vorliegen
     */
    List<String> logs(String serviceId);

    /**
     * @return der letzte Absturz je Service, neueste zuerst
     */
    List<ServiceCrash> lastCrashes();

    /**
     * @return verbleibendes Absturz-Budget je Service-Name
     */
    Map<String, CrashBudget> crashBudgets();
}
