package dev.bytestorm.core.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertFileSinkTest {

    @Test
    void writesRaisedAndResolvedOnOneLineEach(@TempDir Path directory) throws IOException {
        AlertFileSink sink = new AlertFileSink(directory.resolve("logs").resolve(AlertFileSink.FILE_NAME));

        sink.raised(Alert.critical("disk", "Nur 1 GiB frei\nbitte aufräumen"));
        sink.resolved(Alert.critical("disk", "Nur 1 GiB frei\nbitte aufräumen"));

        List<String> lines = Files.readAllLines(sink.file());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("ALARM CRITICAL [disk]"));
        assertTrue(lines.get(0).contains("Nur 1 GiB frei bitte aufräumen"),
                "Zeilenumbrüche würden die Spur unlesbar machen");
        assertTrue(lines.get(1).contains("BEHOBEN"));
    }

    @Test
    void appendsAcrossInstances(@TempDir Path directory) throws IOException {
        Path file = directory.resolve(AlertFileSink.FILE_NAME);
        new AlertFileSink(file).raised(Alert.warning("a", "erster"));
        new AlertFileSink(file).raised(Alert.warning("b", "zweiter"));

        assertEquals(2, Files.readAllLines(file).size());
    }
}
