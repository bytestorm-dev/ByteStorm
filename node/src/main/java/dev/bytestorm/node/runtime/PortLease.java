package dev.bytestorm.node.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Reservierter Service-Port, der genau einmal freigegeben wird (§12.4).
 *
 * <p>Die Runtime schließt die Lease, wenn der Prozess/Container endet — auch bei
 * einem Crash. Damit geht kein Port über Neustarts hinweg verloren, selbst wenn
 * der Node den Stop nicht mehr selbst ausführt.
 */
public final class PortLease implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PortLease.class);

    private final int port;
    private final IntConsumer release;
    private final AtomicBoolean released = new AtomicBoolean();

    private PortLease(int port, IntConsumer release) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port muss zwischen 1 und 65535 liegen: " + port);
        }
        this.port = port;
        this.release = Objects.requireNonNull(release, "release");
    }

    /**
     * @param port    reservierter Port
     * @param release Freigabe, z. B. {@code PortAllocator::release}
     * @return die Lease
     */
    public static PortLease of(int port, IntConsumer release) {
        return new PortLease(port, release);
    }

    /**
     * Lease ohne Freigabe, z. B. für Tests oder feste Ports.
     *
     * @param port Port
     * @return die Lease
     */
    public static PortLease fixed(int port) {
        return new PortLease(port, ignored -> {
        });
    }

    /**
     * @return der reservierte Port
     */
    public int port() {
        return port;
    }

    /**
     * @return {@code true}, wenn die Lease bereits freigegeben wurde
     */
    public boolean released() {
        return released.get();
    }

    @Override
    public void close() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        try {
            release.accept(port);
        } catch (RuntimeException e) {
            // Beim Aufräumen nach einem Crash darf der Node nicht sterben.
            log.warn("Port {} konnte nicht freigegeben werden: {}", port, e.toString());
        }
    }

    @Override
    public String toString() {
        return "PortLease[" + port + (released.get() ? ", freigegeben" : "") + "]";
    }
}
