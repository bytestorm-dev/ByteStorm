package dev.bytestorm.node.runtime;

import dev.bytestorm.proto.LogLine;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class BatchingLogPublisherTest {

    private final List<LogLine> published = Collections.synchronizedList(new ArrayList<>());

    @Test
    void publishesImmediatelyWhenTheBatchIsFull() {
        BatchingLogPublisher publisher = new BatchingLogPublisher(published::add, Duration.ofSeconds(10), 2);

        publisher.publish(line("a"));
        publisher.publish(line("b"));

        assertEquals(2, published.size());
        publisher.close();
    }

    @Test
    void flushesOnTheInterval() throws Exception {
        BatchingLogPublisher publisher = new BatchingLogPublisher(published::add, Duration.ofMillis(50), 100);

        publisher.publish(line("a"));

        await(() -> published.size() == 1, "Zeile wurde nicht nach dem Intervall veröffentlicht");
        publisher.close();
    }

    @Test
    void closeFlushesTheRest() {
        BatchingLogPublisher publisher = new BatchingLogPublisher(published::add, Duration.ofSeconds(10), 100);
        publisher.publish(line("a"));
        publisher.publish(line("b"));

        publisher.close();

        assertEquals(2, published.size());
    }

    @Test
    void ignoresLinesAfterClose() {
        BatchingLogPublisher publisher = new BatchingLogPublisher(published::add, Duration.ofSeconds(10), 100);
        publisher.close();

        publisher.publish(line("late"));

        assertEquals(0, published.size());
    }

    @Test
    void aFailingSinkDoesNotBreakThePublisher() {
        BatchingLogPublisher publisher = new BatchingLogPublisher(line -> {
            throw new IllegalStateException("Redis weg");
        }, Duration.ofSeconds(10), 1);

        publisher.publish(line("a"));
        publisher.publish(line("b"));

        publisher.close();
    }

    private static LogLine line(String text) {
        return LogLine.newBuilder().setService("Lobby-1").setLine(text).build();
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail(message);
    }
}
