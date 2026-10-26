package dev.bytestorm.node.runtime;

import dev.bytestorm.proto.LogLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceLogsTest {

    @TempDir
    Path temp;

    private final List<LogLine> published = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean publisherClosed = new AtomicBoolean();

    @Test
    void writesToTheRingBufferFileAndPublisher() throws IOException {
        try (ServiceLogs logs = logs(10)) {
            logs.accept("OUT", "hello world");

            assertEquals(1, logs.snapshot().size());
            assertTrue(logs.snapshot().get(0).contains("[OUT] hello world"));
            assertTrue(Files.readString(logs.file()).contains("[OUT] hello world"));
            assertEquals(1, published.size());
            assertEquals("Lobby-1", published.get(0).getService());
            assertEquals("hello world", published.get(0).getLine());
        }
    }

    @Test
    void stripsAnsiSequences() throws IOException {
        try (ServiceLogs logs = logs(10)) {
            logs.accept("ERR", "\u001B[31mred\u001B[0m and \u001B]0;title\u0007plain");

            assertEquals("red and plain", published.get(0).getLine());
            assertTrue(logs.snapshot().get(0).contains("[ERR] red and plain"), logs.snapshot().get(0));
        }
    }

    @Test
    void keepsOnlyTheLastLines() throws IOException {
        try (ServiceLogs logs = logs(3)) {
            for (int i = 1; i <= 5; i++) {
                logs.accept("OUT", "line-" + i);
            }

            List<String> tail = logs.snapshot();
            assertEquals(3, tail.size());
            assertTrue(tail.get(0).endsWith("line-3"));
            assertTrue(tail.get(2).endsWith("line-5"));
        }
    }

    @Test
    void ignoresBlankLines() throws IOException {
        try (ServiceLogs logs = logs(10)) {
            logs.accept("OUT", "  \u001B[0m ");

            assertTrue(logs.snapshot().isEmpty());
            assertTrue(published.isEmpty());
        }
    }

    @Test
    void afterCloseNothingIsWrittenAnymore() throws IOException {
        ServiceLogs logs = logs(10);
        logs.accept("OUT", "before");
        logs.close();

        logs.accept("OUT", "after");

        assertEquals(1, logs.snapshot().size());
        assertTrue(publisherClosed.get());
        assertFalse(Files.readString(logs.file()).contains("after"));
    }

    @Test
    void appendsToAnExistingLog() throws IOException {
        Path logDirectory = temp.resolve("logs/Lobby-1");
        Files.createDirectories(logDirectory);
        Files.writeString(logDirectory.resolve("latest.log"), "old\n");

        try (ServiceLogs logs = logs(10)) {
            logs.accept("OUT", "new");
        }

        String content = Files.readString(logDirectory.resolve("latest.log"));
        assertTrue(content.contains("old"));
        assertTrue(content.contains("new"));
    }

    private ServiceLogs logs(int ringSize) throws IOException {
        return new ServiceLogs("Lobby-1", temp.resolve("logs/Lobby-1"), ringSize, new LogPublisher() {
            @Override
            public void publish(LogLine line) {
                published.add(line);
            }

            @Override
            public void close() {
                publisherClosed.set(true);
            }
        });
    }
}
