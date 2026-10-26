package dev.bytestorm.core.redis;

import dev.bytestorm.api.service.PortRange;
import org.redisson.api.RLock;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Vergibt Ports über Redis, damit auch mehrere Prozesse keine Doppelvergabe
 * erzeugen (§12.4).
 *
 * <p>Der Redis-Lock serialisiert die Vergabe; zusätzlich wird geprüft, ob der
 * Port lokal noch frei ist ({@link ServerSocket}).
 */
public final class PortAllocator {

    private static final long LOCK_WAIT_SECONDS = 5;

    private final RSet<Integer> used;
    private final RLock lock;

    /**
     * @param client verbundener Redisson-Client
     */
    public PortAllocator(RedissonClient client) {
        this.used = client.getSet(RedisKeys.PORTS);
        this.lock = client.getLock(RedisKeys.PORTS + ":lock");
    }

    /**
     * Sucht den ersten freien Port im Bereich.
     *
     * @param range erlaubter Bereich
     * @return reservierter Port
     * @throws PortExhaustedException wenn kein Port frei ist oder der Lock nicht erteilt wurde
     */
    public int allocate(PortRange range) {
        acquireLock();
        try {
            for (int port = range.start(); port <= range.end(); port++) {
                if (!used.contains(port) && isLocallyFree(port)) {
                    used.add(port);
                    return port;
                }
            }
        } finally {
            releaseLock();
        }
        throw new PortExhaustedException("Kein freier Port in " + range.start() + "-" + range.end());
    }

    /**
     * Reserviert einen konkreten Port, z. B. nach einem Neustart.
     *
     * @param range erlaubter Bereich
     * @param port  gewünschter Port
     * @return {@code true}, wenn der Port reserviert wurde
     * @throws PortExhaustedException wenn der Lock nicht erteilt wurde
     */
    public boolean reserve(PortRange range, int port) {
        if (!range.contains(port)) {
            throw new IllegalArgumentException("Port " + port + " liegt nicht in " + range.start() + "-" + range.end());
        }
        acquireLock();
        try {
            if (used.contains(port) || !isLocallyFree(port)) {
                return false;
            }
            used.add(port);
            return true;
        } finally {
            releaseLock();
        }
    }

    /**
     * @param port freizugebender Port
     * @return {@code true}, wenn der Port reserviert war
     */
    public boolean release(int port) {
        return used.remove(port);
    }

    /**
     * @return alle aktuell vergebenen Ports
     */
    public Set<Integer> allocated() {
        return Set.copyOf(used.readAll());
    }

    private void acquireLock() {
        try {
            if (!lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new PortExhaustedException("Port-Lock konnte nicht erworben werden");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PortExhaustedException("Unterbrochen beim Warten auf den Port-Lock");
        }
    }

    private void releaseLock() {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private static boolean isLocallyFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
