package dev.bytestorm.core.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;

/**
 * Hängt Alarme an {@code logs/alerts.log} an — eine Zeile je Ereignis.
 *
 * <p>Die Datei ist für den Betrieb gedacht, nicht für den Menschen davor: Sie
 * wird nicht rotiert (Alarme sind selten, und wer sie aufräumt, will sie nicht
 * verlieren) und lässt sich mit einem Log-Shipper oder einem einfachen
 * {@code grep} auswerten. Log und Datei sind getrennt, weil das Log bei
 * Rotation verschwindet, die Alarmspur aber den Vorfall überdauern soll.
 *
 * <p>Jedes Ereignis öffnet und schließt die Datei. Alarme fallen im
 * Minutenbereich an, dafür hält der Sink keinen Deskriptor offen und kann
 * keinen halb geschriebenen Puffer verlieren.
 */
public final class AlertFileSink implements AlertSink {

    /** Name der Alarmdatei im Log-Verzeichnis. */
    public static final String FILE_NAME = "alerts.log";

    private static final Logger log = LoggerFactory.getLogger(AlertFileSink.class);

    private final Path file;

    /**
     * @param file Zieldatei; das Verzeichnis wird bei Bedarf angelegt
     */
    public AlertFileSink(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    @Override
    public void raised(Alert alert) {
        write("ALARM", alert);
    }

    @Override
    public void resolved(Alert alert) {
        write("BEHOBEN", alert);
    }

    private synchronized void write(String kind, Alert alert) {
        String line = Instant.now() + " " + kind + " " + alert.severity()
                + " [" + alert.source() + "] " + oneLine(alert.message())
                + System.lineSeparator();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Ein volles oder schreibgeschütztes Verzeichnis ist selbst ein Alarm,
            // aber nicht der Ort, an dem er gemeldet werden kann.
            log.warn("Alarmdatei {} nicht schreibbar: {}", file, e.getMessage());
        }
    }

    private static String oneLine(String message) {
        return message.replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * @return die Zieldatei
     */
    public Path file() {
        return file;
    }
}
