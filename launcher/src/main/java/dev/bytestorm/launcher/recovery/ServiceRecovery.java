package dev.bytestorm.launcher.recovery;

import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.launcher.layout.CloudLayout;
import dev.bytestorm.node.lifecycle.ServiceDefinition;
import dev.bytestorm.node.lifecycle.ServiceLifecycleException;
import dev.bytestorm.node.lifecycle.ServiceManager;
import dev.bytestorm.node.lifecycle.ServicePid;
import dev.bytestorm.node.runtime.PortLease;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceGroup;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/**
 * Räumt beim Start Prozesse aus einem früheren Cloud-Lauf auf (§5.1, §7.1).
 *
 * <p>Die Registry in Redis deckt diesen Fall nicht ab: Sie wird beim Start
 * geleert, die Prozesse laufen aber weiter. Ohne diesen Schritt wäre ein
 * überlebender Server unsichtbar und unbedienbar, würde seinen Port halten und
 * jeden Neustart mit „Kein freier Port“ scheitern lassen — und seine Bridge
 * bekäme auf jeden Handshake ein „nicht registriert“.
 *
 * <p>Je PID-Eintrag gibt es drei Ausgänge:
 * <ul>
 *   <li><b>übernehmen</b> — der Prozess lebt und seine Gruppe existiert: Er wird
 *       unter seiner alten ID in den {@link ServiceManager} gehängt; die Bridge
 *       setzt ihn über den nächsten Heartbeat auf {@code ONLINE}.</li>
 *   <li><b>aufräumen</b> — der Prozess ist weg: Der Eintrag wird gelöscht und die
 *       alte Port-Reservierung freigegeben.</li>
 *   <li><b>beenden</b> — der Prozess lebt, aber seine Gruppe gibt es nicht mehr:
 *       Ohne Gruppe gibt es keine Definition; der Prozess würde für immer
 *       unverwaltet weiterlaufen. Er wird beendet und der Eintrag entfernt.</li>
 * </ul>
 *
 * <p>Ein unlesbarer Eintrag führt bewusst nicht zum Beenden: Aus einer kaputten
 * Datei lässt sich nicht ableiten, dass der Prozess zu dieser Cloud gehört.
 * Solche Prozesse bleiben laufen und werden gemeldet.
 */
@Slf4j
public final class ServiceRecovery {

    /** Frist, die ein zu beendender Fremdprozess zum Auslaufen bekommt. */
    private static final long KILL_WAIT_SECONDS = 5;

    private final CloudLayout layout;
    private final Function<String, Optional<ServiceGroup>> groups;
    private final RedisSettings redis;
    private final IntConsumer releasePort;
    private final ServiceManager services;

    /**
     * @param layout      Cloud-Layout mit {@code static/} und {@code running/}
     * @param groups      liefert eine Gruppe nach Namen
     * @param redis       Redis-Verbindung, die eine übernommene Definition mitführt
     * @param releasePort gibt eine verwaiste Port-Reservierung frei
     * @param services    Ziel für übernommene Services
     */
    public ServiceRecovery(CloudLayout layout,
                           Function<String, Optional<ServiceGroup>> groups,
                           RedisSettings redis,
                           IntConsumer releasePort,
                           ServiceManager services) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.redis = Objects.requireNonNull(redis, "redis");
        this.releasePort = Objects.requireNonNull(releasePort, "releasePort");
        this.services = Objects.requireNonNull(services, "services");
    }

    /**
     * Prüft alle Service-Ordner auf einen PID-Eintrag.
     *
     * @return was übernommen, aufgeräumt und ausgelassen wurde
     */
    public Report run() {
        List<String> adopted = new ArrayList<>();
        List<String> reaped = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> adoptedIds = new LinkedHashSet<>();
        Set<Integer> adoptedPorts = new LinkedHashSet<>();

        for (Path directory : serviceDirectories()) {
            if (!Files.isRegularFile(ServicePid.fileIn(directory))) {
                continue;
            }
            Optional<ServicePid> parsed = ServicePid.read(directory);
            if (parsed.isEmpty()) {
                ServicePid.delete(directory);
                skipped.add(directory.getFileName().toString());
                log.warn("PID-Eintrag in {} ist unlesbar und wurde entfernt — ein laufender Prozess dort "
                        + "bleibt unverwaltet", directory);
                continue;
            }
            ServicePid entry = parsed.get();
            if (!entry.alive()) {
                ServicePid.delete(directory);
                releasePort.accept(entry.port());
                reaped.add(entry.name());
                log.info("Service '{}' läuft nicht mehr (PID {}) — PID-Eintrag entfernt, Port {} freigegeben",
                        entry.name(), entry.pid(), entry.port());
                continue;
            }

            Optional<ServiceGroup> group = groups.apply(entry.group());
            if (group.isEmpty()) {
                terminate(entry);
                ServicePid.delete(directory);
                releasePort.accept(entry.port());
                reaped.add(entry.name());
                log.warn("Gruppe '{}' von '{}' existiert nicht mehr — laufender Prozess (PID {}) beendet",
                        entry.group(), entry.name(), entry.pid());
                continue;
            }

            try {
                services.adopt(definition(entry, group.get(), directory),
                        PortLease.of(entry.port(), releasePort), entry.pid());
                adopted.add(entry.name());
                adoptedIds.add(entry.serviceId());
                adoptedPorts.add(entry.port());
                log.info("Service '{}' (PID {}) übernommen — er wird wieder ONLINE, sobald seine Bridge "
                        + "sich meldet", entry.name(), entry.pid());
            } catch (ServiceLifecycleException | RuntimeException e) {
                skipped.add(entry.name());
                log.warn("Service '{}' (PID {}) konnte nicht übernommen werden: {}",
                        entry.name(), entry.pid(), e.getMessage());
            }
        }

        return new Report(List.copyOf(adopted), List.copyOf(reaped), List.copyOf(skipped),
                Set.copyOf(adoptedIds), Set.copyOf(adoptedPorts));
    }

    private ServiceDefinition definition(ServicePid entry, ServiceGroup group, Path directory) {
        int memory = group.getMemory() > 0 ? group.getMemory() : 1024;
        LaunchOptions options = LaunchOptions.builder()
                .memoryMb(memory)
                .runtime(RuntimeType.PROCESS)
                .build();
        String bridgeJar = entry.bridgeJar();
        return new ServiceDefinition(
                ServiceId.parse(entry.serviceId()),
                entry.name(),
                group,
                directory,
                Path.of(entry.platformJar()),
                bridgeJar.isBlank() ? null : Path.of(bridgeJar),
                options,
                redis);
    }

    private void terminate(ServicePid entry) {
        Optional<ProcessHandle> process = ProcessHandle.of(entry.pid()).filter(ProcessHandle::isAlive);
        if (process.isEmpty()) {
            return;
        }
        ProcessHandle handle = process.get();
        handle.destroyForcibly();
        try {
            handle.onExit().get(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // Prozess ist ohnehin beendet.
        } catch (TimeoutException e) {
            log.warn("Prozess {} von '{}' ließ sich nicht beenden — er bleibt unverwaltet",
                    entry.pid(), entry.name());
        }
    }

    private List<Path> serviceDirectories() {
        List<Path> directories = new ArrayList<>();
        collect(layout.staticServices(), directories);
        collect(layout.running(), directories);
        directories.sort(Path::compareTo);
        return directories;
    }

    private static void collect(Path root, List<Path> target) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(Files::isDirectory).forEach(target::add);
        } catch (IOException e) {
            log.warn("Service-Ordner {} nicht lesbar: {}", root, e.getMessage());
        }
    }

    /**
     * Ergebnis eines Übernahme-Laufs.
     *
     * @param adopted      übernommene Services
     * @param reaped       entfernte Einträge und beendete Prozesse
     * @param skipped      laufende Prozesse, die unverwaltet bleiben
     * @param adoptedIds   Service-IDs der übernommenen Services, für den Abgleich
     * @param adoptedPorts Ports der übernommenen Services, für den Abgleich
     */
    public record Report(List<String> adopted,
                         List<String> reaped,
                         List<String> skipped,
                         Set<String> adoptedIds,
                         Set<Integer> adoptedPorts) {

        /**
         * @return {@code true}, wenn kein Service-Ordner einen PID-Eintrag hatte
         */
        public boolean isEmpty() {
            return adopted.isEmpty() && reaped.isEmpty() && skipped.isEmpty();
        }

        /**
         * @return Kurzfassung für das Log
         */
        public String describe() {
            if (isEmpty()) {
                return "keine laufenden Prozesse aus einem früheren Start";
            }
            StringBuilder text = new StringBuilder()
                    .append(adopted.size()).append(" übernommen, ")
                    .append(reaped.size()).append(" aufgeräumt");
            if (!skipped.isEmpty()) {
                text.append(", ").append(skipped.size()).append(" unverwaltet");
            }
            return text.toString();
        }
    }
}
