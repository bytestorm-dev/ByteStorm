package dev.bytestorm.node.runtime;

import dev.bytestorm.core.redis.LogTopic;
import dev.bytestorm.proto.LogLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Sammelt Logzeilen und veröffentlicht sie gesammelt, damit ein gesprächiger
 * Server Redis nicht mit einem Roundtrip pro Zeile flutet (§12.2).
 *
 * <p>Geflusht wird, sobald {@code batchSize} Zeilen warten oder das Intervall
 * abgelaufen ist; {@link #close()} schreibt den Rest. Fehler beim Publizieren
 * werden geloggt, aber nie an den Prozess weitergegeben.
 */
public final class BatchingLogPublisher implements LogPublisher {

    private static final Logger log = LoggerFactory.getLogger(BatchingLogPublisher.class);

    /** Default-Intervall zwischen zwei Flushes. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofMillis(250);

    /** Default-Batchgröße. */
    public static final int DEFAULT_BATCH_SIZE = 50;

    private final Consumer<LogLine> sink;
    private final int batchSize;
    private final Queue<LogLine> queue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object flushLock = new Object();

    /**
     * @param topic Redis-Topic
     */
    public BatchingLogPublisher(LogTopic topic) {
        this(topic::publish, DEFAULT_INTERVAL, DEFAULT_BATCH_SIZE);
    }

    /**
     * @param sink      Ziel der Zeilen
     * @param interval  Flush-Intervall
     * @param batchSize Anzahl Zeilen, ab der sofort geflusht wird
     */
    public BatchingLogPublisher(Consumer<LogLine> sink, Duration interval, int batchSize) {
        this.sink = Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval muss positiv sein: " + interval);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize muss positiv sein: " + batchSize);
        }
        this.batchSize = batchSize;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "bytestorm-log-flush");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::flush, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void publish(LogLine line) {
        Objects.requireNonNull(line, "line");
        if (closed.get()) {
            return;
        }
        queue.add(line);
        if (queue.size() >= batchSize) {
            flush();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdownNow();
            flush();
        }
    }

    /**
     * Schreibt alle wartenden Zeilen. Öffentlich für Tests und Shutdown.
     */
    public void flush() {
        synchronized (flushLock) {
            List<LogLine> batch = new ArrayList<>(batchSize);
            LogLine line;
            while ((line = queue.poll()) != null) {
                batch.add(line);
                if (batch.size() >= batchSize) {
                    publishBatch(batch);
                    batch = new ArrayList<>(batchSize);
                }
            }
            if (!batch.isEmpty()) {
                publishBatch(batch);
            }
        }
    }

    private void publishBatch(List<LogLine> batch) {
        try {
            batch.forEach(sink);
        } catch (RuntimeException e) {
            log.warn("Logs konnten nicht veröffentlicht werden ({} Zeilen): {}", batch.size(), e.toString());
        }
    }
}
