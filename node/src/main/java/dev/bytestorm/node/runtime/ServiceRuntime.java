package dev.bytestorm.node.runtime;

import dev.bytestorm.api.service.ServiceHandle;

import java.time.Duration;
import java.util.List;

/**
 * Startet Services in einer Process- oder Docker-Runtime (Konzept §12.1).
 *
 * <p>Das Interface ist die einzige Stelle, an der der Node Prozesse oder
 * Container anfasst; alles darüber arbeitet mit {@link ServiceHandle} und
 * {@link LaunchOptions}. {@link #start} kehrt zurück, sobald Process bzw.
 * Container existiert — nicht erst, wenn die Bridge {@code ready} meldet
 * (§9.2). {@link #isAlive} sagt deshalb nur, ob der Prozess/Container noch
 * läuft, nichts über die Gesundheit des Services.
 */
public interface ServiceRuntime {

    /**
     * Startet einen Service.
     *
     * @param launch Service-ID, Name, Verzeichnisse, Jars und Startparameter
     * @return Handle auf den laufenden Prozess/Container
     * @throws ServiceRuntimeException wenn der Start fehlschlägt
     */
    ServiceHandle start(ServiceLaunch launch);

    /**
     * Fährt einen Service graceful herunter und killt ihn nach Ablauf der Frist.
     *
     * @param handle  laufender Service
     * @param timeout Frist für den graceful Stop
     * @throws ServiceRuntimeException wenn weder Stop noch Kill gelingen
     */
    void stop(ServiceHandle handle, Duration timeout);

    /**
     * Beendet einen Service sofort.
     *
     * @param handle laufender Service
     * @throws ServiceRuntimeException wenn der Prozess/Container nicht beendet werden kann
     */
    void kill(ServiceHandle handle);

    /**
     * @param handle Service
     * @return {@code true}, solange Prozess/Container läuft
     */
    boolean isAlive(ServiceHandle handle);

    /**
     * @param handle Service
     * @return die zuletzt gesehenen Logzeilen, älteste zuerst; leer ohne Logs
     */
    List<String> logs(ServiceHandle handle);
}
