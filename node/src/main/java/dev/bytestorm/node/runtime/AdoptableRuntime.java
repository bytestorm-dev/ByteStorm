package dev.bytestorm.node.runtime;

import dev.bytestorm.api.service.ServiceHandle;

/**
 * Runtime, die einen noch laufenden Prozess übernehmen kann (§5.1, §7.1).
 *
 * <p>Nach einem Cloud-Absturz kann ein Server weiterlaufen, obwohl die Cloud ihn
 * nicht mehr kennt. Eine adoptierbare Runtime bindet einen solchen Prozess
 * anhand seiner Prozess-ID wieder an einen {@link ServiceHandle}, damit Stop,
 * Heartbeat-Prüfung und Crash-Pfad ihn wieder erreichen.
 *
 * <p>Nur wo diese Fähigkeit vorhanden ist, schreibt der Lifecycle einen
 * {@code ServicePid}-Eintrag in den Service-Ordner — er ist nur dort nützlich.
 */
public interface AdoptableRuntime {

    /**
     * Übernimmt einen laufenden Prozess.
     *
     * @param launch Service-Beschreibung inklusive Port-Lease
     * @param pid    Prozess-ID
     * @return Handle auf den laufenden Prozess
     * @throws ServiceRuntimeException wenn der Prozess nicht (mehr) läuft
     */
    ServiceHandle adopt(ServiceLaunch launch, long pid);
}
