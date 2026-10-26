package dev.bytestorm.node.runtime;

import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceGroup;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Wählt die Runtime eines Services (Konzept §12): global aus
 * {@code cloud.json}, pro Gruppe über {@link ServiceGroup#hasRuntime()}
 * überschrieben.
 *
 * <p>Die verfügbaren Runtimes werden injiziert, damit der Node nur die
 * tatsächlich konfigurierten Implementierungen kennt und Tests ohne Docker
 * auskommen.
 */
public final class RuntimeFactory {

    private final Map<RuntimeType, ServiceRuntime> runtimes;

    /**
     * @param runtimes verfügbare Runtimes je Typ; fehlende Typen führen erst
     *                 bei der Auswahl zu einem Fehler
     */
    public RuntimeFactory(Map<RuntimeType, ServiceRuntime> runtimes) {
        Objects.requireNonNull(runtimes, "runtimes");
        EnumMap<RuntimeType, ServiceRuntime> copy = new EnumMap<>(RuntimeType.class);
        runtimes.forEach((type, runtime) -> {
            Objects.requireNonNull(type, "runtime type");
            Objects.requireNonNull(runtime, "runtime for " + type);
            copy.put(type, runtime);
        });
        this.runtimes = Collections.unmodifiableMap(copy);
    }

    /**
     * @param process Process-Runtime
     * @param docker  Docker-Runtime, darf {@code null} sein wenn nicht genutzt
     * @return Factory mit beiden Runtimes
     */
    public static RuntimeFactory of(ServiceRuntime process, ServiceRuntime docker) {
        EnumMap<RuntimeType, ServiceRuntime> runtimes = new EnumMap<>(RuntimeType.class);
        runtimes.put(RuntimeType.PROCESS, Objects.requireNonNull(process, "process"));
        if (docker != null) {
            runtimes.put(RuntimeType.DOCKER, docker);
        }
        return new RuntimeFactory(runtimes);
    }

    /**
     * @param type Runtime-Typ
     * @return die Runtime
     * @throws IllegalStateException wenn der Typ nicht verfügbar ist
     */
    public ServiceRuntime forType(RuntimeType type) {
        Objects.requireNonNull(type, "type");
        ServiceRuntime runtime = runtimes.get(type);
        if (runtime == null) {
            throw new IllegalStateException("Runtime " + type + " ist nicht verfügbar; verfügbar: " + runtimes.keySet());
        }
        return runtime;
    }

    /**
     * @param global globale Runtime aus {@code cloud.json}
     * @param group  Gruppe, deren Override Vorrang hat
     * @return die effektive Runtime
     * @throws IllegalStateException wenn die effektive Runtime nicht verfügbar ist
     */
    public ServiceRuntime forGroup(RuntimeType global, ServiceGroup group) {
        Objects.requireNonNull(group, "group");
        RuntimeType type = group.hasRuntime() ? group.getRuntime() : global;
        return forType(type);
    }
}
