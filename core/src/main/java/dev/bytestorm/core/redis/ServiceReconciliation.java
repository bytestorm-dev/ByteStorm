package dev.bytestorm.core.redis;

import dev.bytestorm.proto.ServiceInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gleicht den Live-Zustand in Redis beim Start gegen die Realität ab (§7.1, §15).
 *
 * <p>Nach einem Neustart ist der Node leer, Redis aber nicht: {@code bs:services}
 * kann noch bis zur TTL Einträge des vorherigen Laufs enthalten, {@code bs:ports}
 * reservierte Ports und {@code bs:counter:<group>} einen Zählerstand, der nicht
 * mehr zu den vorhandenen Service-Ordnern passt. Ohne Abgleich zeigt die Registry
 * Services als {@code ONLINE}, die niemand mehr bedient, und neue Services laufen
 * Gefahr, Namen bestehender statischer Ordner zu erben.
 *
 * <p>Bewusste Entscheidung: Diese Cloud ist Single-Node (§5). Ein Eintrag ohne
 * zugehörigen Service ist deshalb ein Rest des vorherigen Laufs und wird
 * entfernt. Ein noch laufender Prozess ohne Bridge wird <b>nicht</b> übernommen;
 * er muss von Hand beendet werden — seine Bridge meldet sich sonst mit einer
 * unbekannten Service-ID.
 *
 * <p>Ports werden nur freigegeben, nicht neu vergeben; die Vergabe prüft
 * zusätzlich, ob der Port lokal frei ist ({@link PortAllocator}), ein lebender
 * Listener gewinnt also weiterhin.
 */
@Slf4j
public final class ServiceReconciliation {

    /** {@code <Gruppe>-<Nummer>}, z. B. {@code Lobby-1}; Gruppen dürfen selbst {@code -} enthalten. */
    private static final Pattern SERVICE_NAME = Pattern.compile("^(.+)-(\\d+)$");

    /** {@code running/<Name>-<UUID>}: die Service-ID wird beim Instanznamen angehängt. */
    private static final Pattern SERVICE_ID_SUFFIX = Pattern.compile(
            "-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ServiceRegistryStore services;
    private final PortAllocator ports;
    private final NameAllocator names;

    /**
     * @param services Live-Registry in Redis
     * @param ports    Port-Vergabe
     * @param names    Namensvergabe
     */
    public ServiceReconciliation(ServiceRegistryStore services, PortAllocator ports, NameAllocator names) {
        this.services = Objects.requireNonNull(services, "services");
        this.ports = Objects.requireNonNull(ports, "ports");
        this.names = Objects.requireNonNull(names, "names");
    }

    /**
     * Führt alle Schritte in einem Aufruf aus.
     *
     * @param knownServiceIds IDs, die der Node kennt (beim Start in der Regel leer)
     * @param knownPorts      Ports, die zu bekannten Services gehören
     * @param existingNames   Namen, die es lokal schon gibt (Service-Ordner, Registry)
     * @return was entfernt, freigegeben und angehoben wurde
     */
    public Report run(Collection<String> knownServiceIds,
                      Collection<Integer> knownPorts,
                      Collection<String> existingNames) {
        List<String> removed = removeOrphanedServices(knownServiceIds);
        Set<Integer> released = releaseOrphanedPorts(knownPorts);
        Map<String, Integer> counters = alignCounters(existingNames);
        return new Report(removed, released, counters);
    }

    /**
     * Entfernt Registry-Einträge, zu denen der Node keinen Service kennt.
     *
     * @param knownServiceIds IDs der bekannten Services
     * @return die entfernten Service-Namen bzw. IDs, alphabetisch
     */
    public List<String> removeOrphanedServices(Collection<String> knownServiceIds) {
        Set<String> known = new LinkedHashSet<>(Objects.requireNonNull(knownServiceIds, "knownServiceIds"));
        List<String> removed = new ArrayList<>();
        for (ServiceInfo info : services.all()) {
            if (known.contains(info.getId())) {
                continue;
            }
            if (services.remove(info.getId())) {
                removed.add(info.getName());
                log.info("Verwaister Registry-Eintrag entfernt: {} ({}, {})",
                        info.getName(), info.getId(), info.getState());
            }
        }
        removed.sort(String::compareTo);
        return List.copyOf(removed);
    }

    /**
     * Gibt Ports frei, die kein bekannter Service mehr belegt.
     *
     * @param knownPorts Ports der bekannten Services
     * @return die freigegebenen Ports, aufsteigend
     */
    public Set<Integer> releaseOrphanedPorts(Collection<Integer> knownPorts) {
        Set<Integer> known = new LinkedHashSet<>(Objects.requireNonNull(knownPorts, "knownPorts"));
        Set<Integer> released = new LinkedHashSet<>();
        for (int port : ports.allocated()) {
            if (known.contains(port)) {
                continue;
            }
            if (ports.release(port)) {
                released.add(port);
            }
        }
        if (!released.isEmpty()) {
            log.info("Verwaiste Port-Reservierungen freigegeben: {}", released);
        }
        return Set.copyOf(released);
    }

    /**
     * Hebt die Namenszähler auf die höchste Nummer an, die lokal schon vergeben ist.
     *
     * @param existingNames Service-Namen aus {@code static/}, {@code running/} und der Registry
     * @return je Gruppe die angehobenen Zählerstände, leer wenn nichts anzupassen war
     */
    public Map<String, Integer> alignCounters(Collection<String> existingNames) {
        Map<String, Integer> highest = new LinkedHashMap<>();
        for (String raw : Objects.requireNonNull(existingNames, "existingNames")) {
            Matcher matcher = SERVICE_NAME.matcher(stripServiceId(raw));
            if (!matcher.matches()) {
                continue;
            }
            int number;
            try {
                number = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException e) {
                // Nummer außerhalb des int-Bereichs — kein von der Cloud vergebener Name.
                continue;
            }
            highest.merge(matcher.group(1), number, Math::max);
        }

        Map<String, Integer> aligned = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : highest.entrySet()) {
            long before = names.current(entry.getKey());
            long after = names.ensureAtLeast(entry.getKey(), entry.getValue());
            if (after != before) {
                aligned.put(entry.getKey(), entry.getValue());
                log.info("Namenszähler für '{}' auf {} angehoben (vorhandener Name '{}')",
                        entry.getKey(), after, entry.getKey() + "-" + entry.getValue());
            }
        }
        return Map.copyOf(aligned);
    }

    private static String stripServiceId(String name) {
        return SERVICE_ID_SUFFIX.matcher(name).replaceFirst("");
    }

    /**
     * Ergebnis eines Abgleichs.
     *
     * @param removedServices  entfernte Registry-Einträge
     * @param releasedPorts    freigegebene Ports
     * @param alignedCounters  angehobene Zähler je Gruppe
     */
    public record Report(List<String> removedServices,
                         Set<Integer> releasedPorts,
                         Map<String, Integer> alignedCounters) {

        /**
         * @return {@code true}, wenn nichts zu tun war
         */
        public boolean isEmpty() {
            return removedServices.isEmpty() && releasedPorts.isEmpty() && alignedCounters.isEmpty();
        }

        /**
         * @return Kurzfassung für das Log
         */
        public String describe() {
            if (isEmpty()) {
                return "nichts abzugleichen";
            }
            return removedServices.size() + " verwaiste Services entfernt, "
                    + releasedPorts.size() + " Ports freigegeben, "
                    + alignedCounters.size() + " Namenszähler angehoben";
        }
    }
}
