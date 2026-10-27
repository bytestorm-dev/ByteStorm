package dev.bytestorm.controller.scaling;

import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.node.lifecycle.ServiceLifecycleException;
import dev.bytestorm.proto.ServiceInfo;

import java.util.Map;

/**
 * Aktionen, die der {@link ScalingManager} ausführt (§11).
 *
 * <p>Der Manager entscheidet nur; das Anlegen einer neuen Service-Definition
 * (Name, Port, Plattform-Jar, Template-Ordner) gehört zu einem späteren
 * Provisioner. Diese Schnittstelle hält die Entscheidungslogik davon frei und
 * erlaubt Tests ohne Node oder Plattform-Download.
 */
public interface ScalingActions {

    /**
     * Startet einen zusätzlichen Service der Gruppe.
     *
     * @param group Gruppenname
     * @return der neu registrierte Service
     * @throws ServiceLifecycleException wenn der Start fehlschlägt
     */
    default ServiceInfo start(String group) throws ServiceLifecycleException {
        return start(group, Map.of());
    }

    /**
     * Startet einen zusätzlichen Service der Gruppe mit zusätzlichen Properties.
     *
     * <p>Die Properties gelten nur für diesen einen Start und überschreiben
     * gleichnamige Einträge der Gruppe (§9); die automatische Skalierung nutzt die
     * Variante ohne, der Start über die API und das REST-Modul die mit Properties.
     *
     * @param group      Gruppenname
     * @param properties zusätzliche Properties, z. B. {@code template}
     * @return der neu registrierte Service
     * @throws ServiceLifecycleException wenn der Start fehlschlägt
     */
    ServiceInfo start(String group, Map<String, String> properties) throws ServiceLifecycleException;

    /**
     * Stoppt einen Service.
     *
     * @param id    Service-ID
     * @param force {@code true} killt sofort
     * @return der gestoppte Service
     * @throws ServiceLifecycleException wenn der Stop fehlschlägt
     */
    ServiceInfo stop(ServiceId id, boolean force) throws ServiceLifecycleException;
}
