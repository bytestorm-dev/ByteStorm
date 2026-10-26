package dev.bytestorm.controller;

import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.node.lifecycle.ServiceDefinition;
import dev.bytestorm.node.lifecycle.ServiceLifecycleException;
import dev.bytestorm.proto.ServiceInfo;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Controller-Fassade für Service-Operationen (§13, §15).
 *
 * <p>Die Schnittstelle hält CLI/REST frei vom {@code ServiceManager} und erlaubt
 * Tests mit einer einfachen Fake-Implementierung.
 */
public interface ServiceController {

    /**
     * @param definition neuer Service
     * @return der gestartete Service
     * @throws ServiceLifecycleException wenn der Start fehlschlägt
     */
    ServiceInfo start(ServiceDefinition definition) throws ServiceLifecycleException;

    /**
     * @param id Service-ID
     * @return der gestartete Service
     * @throws ServiceLifecycleException wenn der Start fehlschlägt
     */
    ServiceInfo start(ServiceId id) throws ServiceLifecycleException;

    /**
     * @param id      Service-ID
     * @param timeout Graceful-Timeout
     * @param force   {@code true} killt sofort
     * @return der gestoppte Service
     * @throws ServiceLifecycleException wenn der Stop fehlschlägt
     */
    ServiceInfo stop(ServiceId id, Duration timeout, boolean force) throws ServiceLifecycleException;

    /**
     * @param id      Service-ID
     * @param timeout Graceful-Timeout des Stops
     * @return der neu gestartete Service
     * @throws ServiceLifecycleException wenn Stop oder Start fehlschlagen
     */
    ServiceInfo restart(ServiceId id, Duration timeout) throws ServiceLifecycleException;

    /**
     * @param id Service-ID
     * @return der sofort beendete Service
     * @throws ServiceLifecycleException wenn der Kill fehlschlägt
     */
    ServiceInfo kill(ServiceId id) throws ServiceLifecycleException;

    /**
     * @param id Service-ID
     * @return der Service nach dem erneuten Anwenden der Templates
     * @throws ServiceLifecycleException wenn das erneute Anwenden fehlschlägt
     */
    ServiceInfo reapply(ServiceId id) throws ServiceLifecycleException;

    /**
     * @param id Service-ID
     * @return der Service im Zustand {@code ONLINE}
     */
    ServiceInfo online(ServiceId id);

    /**
     * @param id Service-ID
     */
    void heartbeat(ServiceId id);

    /**
     * @return IDs der Services mit abgelaufenem Heartbeat
     */
    List<ServiceId> checkHeartbeats();

    /**
     * @return alle Services
     */
    List<ServiceInfo> services();

    /**
     * @param group Gruppenname
     * @return Services der Gruppe
     */
    List<ServiceInfo> byGroup(String group);

    /**
     * @param name Service-Name
     * @return der Service, falls vorhanden
     */
    Optional<ServiceInfo> byName(String name);

    /**
     * @param id      Service-ID
     * @param command Befehl ohne Zeilenende
     * @throws ServiceLifecycleException wenn der Service nicht läuft oder keine Konsole hat
     */
    void sendCommand(ServiceId id, String command) throws ServiceLifecycleException;

    /**
     * @param id Service-ID
     * @return letzte Logzeilen
     */
    List<String> logs(ServiceId id);

    /**
     * @return degradierte Gruppen
     */
    Set<String> degradedGroups();
}
