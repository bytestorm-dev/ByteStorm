package dev.bytestorm.node.lifecycle;

import dev.bytestorm.node.runtime.PortLease;
import dev.bytestorm.proto.ServiceGroup;

/**
 * Reserviert einen Port für einen Service (§12.4).
 */
@FunctionalInterface
public interface ServicePortProvider {

    /**
     * @param group Gruppe, bestimmt den Port-Pool
     * @param name  Service-Name
     * @return die reservierte Lease
     */
    PortLease acquire(ServiceGroup group, String name);
}
