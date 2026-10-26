package dev.bytestorm.node.runtime;

import dev.bytestorm.proto.RuntimeType;

/**
 * Fehler einer {@link ServiceRuntime}, z. B. fehlendes Java-Binary, nicht
 * erreichbarer Docker-Daemon oder ein sofort beendeter Prozess.
 */
public class ServiceRuntimeException extends RuntimeException {

    private final RuntimeType runtime;

    /**
     * @param runtime betroffene Runtime
     * @param message Beschreibung inklusive Service
     */
    public ServiceRuntimeException(RuntimeType runtime, String message) {
        super(message);
        this.runtime = runtime;
    }

    /**
     * @param runtime betroffene Runtime
     * @param message Beschreibung inklusive Service
     * @param cause   Ursache, z. B. ein {@link java.io.IOException}
     */
    public ServiceRuntimeException(RuntimeType runtime, String message, Throwable cause) {
        super(message, cause);
        this.runtime = runtime;
    }

    /**
     * @return die Runtime, in der der Fehler auftrat
     */
    public RuntimeType runtime() {
        return runtime;
    }
}
