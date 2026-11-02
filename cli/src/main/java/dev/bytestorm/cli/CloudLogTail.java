package dev.bytestorm.cli;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Liest die letzten Zeilen aus {@code logs/cloud.log}.
 *
 * <p>Das Dashboard zeigt dieselben Zeilen wie die Konsole. Die Datei kann
 * mehrere Megabyte groß sein, deshalb liest der Tailer nur den letzten Block
 * ({@link #MAX_BYTES}) und schneidet die unvollständige erste Zeile ab. Fehlt
 * die Datei, gibt es keine Zeilen — das Panel entfällt dann einfach.
 */
final class CloudLogTail {

    /** Gelesene Bytes vom Dateiende; genug für die letzten Zeilen eines Tages. */
    static final int MAX_BYTES = 64 * 1024;

    private CloudLogTail() {
    }

    /**
     * @param file  Logdatei, üblicherweise {@code logs/cloud.log}
     * @param lines gewünschte Zeilenzahl
     * @return die letzten Zeilen, älteste zuerst; leer wenn die Datei fehlt
     * @throws IOException wenn die Datei nicht gelesen werden kann
     */
    static List<String> tail(Path file, int lines) throws IOException {
        if (lines <= 0 || file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = channel.size();
            long start = Math.max(0, size - MAX_BYTES);
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate((int) (size - start));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // weiterlesen, bis der Block voll ist
            }
            String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            String[] all = text.split("\\R");
            // Mitten in einer Zeile angefangen: den Rest der angeschnittenen Zeile verwerfen.
            int first = start > 0 ? 1 : 0;
            int from = Math.max(first, all.length - lines);
            List<String> tail = new ArrayList<>(all.length - from);
            for (int index = from; index < all.length; index++) {
                tail.add(all[index]);
            }
            return List.copyOf(tail);
        }
    }
}
