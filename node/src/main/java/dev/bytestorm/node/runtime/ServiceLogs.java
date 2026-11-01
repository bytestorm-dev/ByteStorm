package dev.bytestorm.node.runtime;

import dev.bytestorm.proto.LogLine;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Schreibt die Ausgabe eines Services in einen Ringpuffer und nach
 * {@code logs/<service>/latest.log} (§12.2).
 *
 * <p>Zeilen werden mit Zeitstempel und Kanal ({@code OUT}/{@code ERR})
 * abgelegt; ANSI-Farbcodes werden entfernt, damit Crash-Berichte und CLI lesbar
 * bleiben. Das Schreiben erfolgt sofort, damit auch ein harter Crash die letzten
 * Zeilen auf der Platte lässt.
 *
 * <p>Die Datei wächst nicht unbegrenzt: Ab {@link #DEFAULT_MAX_BYTES} wird sie nach
 * {@code latest.log.1} weitergedreht (eine ältere Drehung wird ersetzt). Ohne das
 * füllt ein dauerhaft redender Service die Platte, ohne dass es jemandem auffällt.
 */
final class ServiceLogs implements AutoCloseable {

    /** Default-Größe des Ringpuffers. */
    static final int DEFAULT_RING_SIZE = 200;

    /** Ab dieser Größe wird {@code latest.log} nach {@code latest.log.1} gedreht. */
    static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[0-9;?]*[ -/]*[@-~]|\\u001B\\][^\\u0007]*\\u0007");

    private final String serviceName;
    private final Path file;
    private final int ringSize;
    private final long maxBytes;
    private final LogPublisher publisher;
    private final Deque<String> ring = new ArrayDeque<>();
    private final Object lock = new Object();
    private BufferedWriter writer;
    private long written;
    private boolean closed;

    /**
     * @param serviceName Service-Name
     * @param logDirectory {@code logs/<service>/}
     * @param ringSize    maximale Zeilenzahl im Puffer
     * @param publisher   Ziel für das Streaming
     * @throws IOException wenn die Logdatei nicht geöffnet werden kann
     */
    ServiceLogs(String serviceName, Path logDirectory, int ringSize, LogPublisher publisher) throws IOException {
        this(serviceName, logDirectory, ringSize, publisher, DEFAULT_MAX_BYTES);
    }

    ServiceLogs(String serviceName, Path logDirectory, int ringSize, LogPublisher publisher,
                long maxBytes) throws IOException {
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
        this.ringSize = ringSize;
        this.maxBytes = maxBytes;
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        Files.createDirectories(logDirectory);
        this.file = logDirectory.resolve("latest.log");
        this.written = Files.exists(file) ? Files.size(file) : 0;
        this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    /**
     * @param service Service-Name
     * @param logRoot Wurzel {@code logs/}
     * @param publisher Publisher
     * @throws IOException wenn die Logdatei nicht geöffnet werden kann
     */
    ServiceLogs(String service, Path logRoot, LogPublisher publisher) throws IOException {
        this(service, logRoot.resolve(service), DEFAULT_RING_SIZE, publisher);
    }

    /**
     * @param stream  {@code OUT} oder {@code ERR}
     * @param rawLine gelesene Zeile, ANSI-Codes erlaubt
     */
    void accept(String stream, String rawLine) {
        if (rawLine == null) {
            return;
        }
        String clean = ANSI.matcher(rawLine.replace("\r", "")).replaceAll("").stripTrailing();
        if (clean.isBlank()) {
            return;
        }
        String stamped = "[" + LocalTime.now().format(TIME) + "] [" + stream + "] " + clean;

        synchronized (lock) {
            if (closed) {
                return;
            }
            ring.addLast(stamped);
            while (ring.size() > ringSize) {
                ring.removeFirst();
            }
            try {
                written += stamped.length() + 1L;
                if (written > maxBytes) {
                    rotate();
                }
                writer.write(stamped);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                // Eine kaputte Logdatei darf den Service nicht beenden.
            }
            publisher.publish(LogLine.newBuilder()
                    .setService(serviceName)
                    .setTime(System.currentTimeMillis())
                    .setLine(clean)
                    .build());
        }
    }

    /**
     * @return die letzten Zeilen, älteste zuerst
     */
    List<String> snapshot() {
        synchronized (lock) {
            return List.copyOf(ring);
        }
    }

    /**
     * Dreht {@code latest.log} nach {@code latest.log.1} und legt die Datei neu an.
     */
    private void rotate() throws IOException {
        writer.flush();
        writer.close();
        Files.move(file, file.resolveSibling("latest.log.1"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        written = 0;
    }

    /**
     * @return die Logdatei
     */
    Path file() {
        return file;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                writer.flush();
                writer.close();
            } catch (IOException e) {
                // Beim Schließen nicht mehr relevant.
            }
            publisher.close();
        }
    }
}
