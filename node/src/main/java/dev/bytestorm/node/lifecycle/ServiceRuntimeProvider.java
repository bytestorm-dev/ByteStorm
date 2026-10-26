package dev.bytestorm.node.lifecycle;

import dev.bytestorm.node.runtime.ServiceRuntime;
import dev.bytestorm.proto.ServiceGroup;

/**
 * Wählt die Runtime einer Gruppe (global oder Gruppen-Override, §12).
 */
@FunctionalInterface
public interface ServiceRuntimeProvider {

    /**
     * @param group Gruppe
     * @return die passende Runtime
     */
    ServiceRuntime runtimeFor(ServiceGroup group);
}
