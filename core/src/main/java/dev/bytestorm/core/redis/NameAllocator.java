package dev.bytestorm.core.redis;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

/**
 * Vergibt Service-Nummern nach Schema {@code <Gruppe>-<Nummer>} (§9.5).
 *
 * <p>Es wird immer die kleinste freie Nummer vergeben; der {@link RAtomicLong}
 * dient als Obergrenze/Startpunkt, damit die Suche nicht bei jedem Aufruf bei 1
 * beginnt. Ein Redis-Lock serialisiert parallele Vergaben.
 */
@RequiredArgsConstructor
public final class NameAllocator {

    private static final long LOCK_WAIT_SECONDS = 5;
    private static final int MAX_ATTEMPTS = 10_000;

    /** Verbundener Redisson-Client. */
    @NonNull
    private final RedissonClient client;

    /**
     * @param group  Gruppenname
     * @param isUsed prüft, ob eine Nummer bereits belegt ist
     * @return die kleinste freie Nummer, beginnend bei 1
     * @throws IllegalStateException wenn keine Nummer frei ist oder der Lock nicht erteilt wurde
     */
    public int allocate(String group, IntPredicate isUsed) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(isUsed, "isUsed");

        RLock lock = client.getLock(RedisKeys.counter(group) + ":lock");
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                throw new IllegalStateException("Namens-Lock für '" + group + "' konnte nicht erworben werden");
            }
            RAtomicLong counter = client.getAtomicLong(RedisKeys.counter(group));
            long hint = Math.max(counter.get(), 1);

            for (int number = 1; number <= MAX_ATTEMPTS; number++) {
                if (!isUsed.test(number)) {
                    if (number > hint) {
                        counter.set(number);
                    }
                    return number;
                }
            }
            throw new IllegalStateException("Keine freie Nummer für Gruppe '" + group + "' gefunden");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Unterbrochen beim Warten auf den Namens-Lock", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * @param group Gruppenname
     * @return der zuletzt vergebene Zählerstand
     */
    public long current(String group) {
        return client.getAtomicLong(RedisKeys.counter(group)).get();
    }

    /**
     * Hebt den Zähler auf mindestens {@code number} an. Der Abgleich beim Start
     * (§7.1) stellt damit sicher, dass bestehende Außenseiter-Ordner — etwa ein
     * statischer Service, der einen Neustart überlebt hat — nicht erneut
     * vergeben werden. Kleinere Werte werden ignoriert: Der Zähler läuft nicht
     * rückwärts, sonst könnten Namen lebender Services wiederverwendet werden.
     *
     * @param group  Gruppenname
     * @param number Nummer, die mindestens erreicht sein muss
     * @return der Zählerstand nach dem Aufruf
     */
    public long ensureAtLeast(String group, long number) {
        Objects.requireNonNull(group, "group");
        // Derselbe Lock wie in allocate(): der Zähler darf nicht zwischen Lesen und Setzen springen.
        RLock lock = client.getLock(RedisKeys.counter(group) + ":lock");
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                throw new IllegalStateException("Namens-Lock für '" + group + "' konnte nicht erworben werden");
            }
            RAtomicLong counter = client.getAtomicLong(RedisKeys.counter(group));
            long current = counter.get();
            if (current >= number) {
                return current;
            }
            counter.set(number);
            return number;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Unterbrochen beim Warten auf den Namens-Lock", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
