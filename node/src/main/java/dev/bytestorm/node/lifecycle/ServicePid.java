package dev.bytestorm.node.lifecycle;

import dev.bytestorm.proto.RuntimeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Zuordnung zwischen einem Service-Ordner und dem Betriebssystem-Prozess, der
 * darin läuft (§5.1, §7.1).
 *
 * <p>Ohne diese Datei ist ein Prozess, der einen Cloud-Absturz überlebt, für den
 * nächsten Start unsichtbar: Er hält seinen Port, taucht aber in keiner Registry
 * auf, lässt sich nicht stoppen und ist auch nicht über die Bridge erreichbar,
 * weil deren Handshake eine unbekannte Service-ID ablehnt. Die Cloud schreibt
 * den Eintrag nach einem erfolgreichen Start und entfernt ihn, sobald der
 * Prozess wirklich weg ist; beim Start liest sie dieselbe Datei, um den Prozess
 * zu übernehmen oder aufzuräumen.
 *
 * <p>Gespeichert wird bewusst der Startzustand (ID, Gruppe, Port, Jar-Pfade) und
 * keine Live-Daten: Der Prozess kann den Ordner nicht mehr verändern, wenn die
 * Cloud tot ist.
 */
public record ServicePid(
        String serviceId,
        String name,
        String group,
        int port,
        long pid,
        RuntimeType runtime,
        long startedAt,
        String platformJar,
        String bridgeJar) {

    /** Name der Datei im Service-Ordner. */
    public static final String FILE_NAME = ".bytestorm.pid";

    private static final Logger log = LoggerFactory.getLogger(ServicePid.class);

    /**
     * @param serviceId   Service-ID als UUID
     * @param name        Service-Name nach Schema {@code <Gruppe>-<Nummer>}
     * @param group       Gruppenname
     * @param port        reservierter Port
     * @param pid         Prozess-ID
     * @param runtime     Runtime, in der der Prozess läuft
     * @param startedAt   Startzeit in Epochen-Millisekunden
     * @param platformJar Pfad der Plattform-Jar beim Start
     * @param bridgeJar   Pfad der Bridge-Jar oder leer
     */
    public ServicePid {
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(platformJar, "platformJar");
        bridgeJar = bridgeJar == null ? "" : bridgeJar;
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port liegt außerhalb von 1-65535: " + port);
        }
        if (pid <= 0) {
            throw new IllegalArgumentException("pid muss positiv sein: " + pid);
        }
    }

    /**
     * @param definition  Service, wie er gestartet wurde
     * @param port        reservierter Port
     * @param processId   Prozess-ID, wie sie die Runtime meldet
     * @return der Eintrag für den Service-Ordner
     * @throws IllegalArgumentException wenn die Runtime keine numerische Prozess-ID liefert
     */
    static ServicePid of(ServiceDefinition definition, int port, String processId) {
        return new ServicePid(
                definition.id().toString(),
                definition.name(),
                definition.group().getName(),
                port,
                Long.parseLong(processId),
                RuntimeType.PROCESS,
                System.currentTimeMillis(),
                definition.platformJar().toString(),
                definition.bridgeJar() == null ? "" : definition.bridgeJar().toString());
    }

    /**
     * @param directory Service-Ordner
     * @return der Pfad der PID-Datei, unabhängig davon, ob sie existiert
     */
    public static Path fileIn(Path directory) {
        return Objects.requireNonNull(directory, "directory").resolve(FILE_NAME);
    }

    /**
     * @return {@code true}, solange der Prozess mit dieser ID läuft
     */
    public boolean alive() {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * Schreibt den Eintrag atomar in den Service-Ordner.
     *
     * @param directory Service-Ordner
     * @throws IOException wenn die Datei nicht geschrieben werden kann
     */
    public void write(Path directory) throws IOException {
        Files.createDirectories(directory);
        Properties properties = new Properties();
        properties.setProperty("serviceId", serviceId);
        properties.setProperty("name", name);
        properties.setProperty("group", group);
        properties.setProperty("port", Integer.toString(port));
        properties.setProperty("pid", Long.toString(pid));
        properties.setProperty("runtime", runtime.name());
        properties.setProperty("startedAt", Long.toString(startedAt));
        properties.setProperty("platformJar", platformJar);
        properties.setProperty("bridgeJar", bridgeJar);

        Path file = fileIn(directory);
        Path temp = file.resolveSibling(FILE_NAME + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            // store() maskiert Pfade korrekt, im Gegensatz zu handgeschriebenen Zeilen.
            properties.store(writer, "ByteStorm Service-PID");
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * @param directory Service-Ordner
     * @return der Eintrag, leer wenn keine Datei liegt oder sie unlesbar ist
     */
    public static Optional<ServicePid> read(Path directory) {
        Path file = fileIn(directory);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ServicePid(
                    required(properties, "serviceId"),
                    required(properties, "name"),
                    required(properties, "group"),
                    Integer.parseInt(required(properties, "port")),
                    Long.parseLong(required(properties, "pid")),
                    RuntimeType.valueOf(properties.getProperty("runtime", RuntimeType.PROCESS.name())),
                    Long.parseLong(properties.getProperty("startedAt", "0")),
                    required(properties, "platformJar"),
                    properties.getProperty("bridgeJar", "")));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Entfernt den Eintrag, ohne den Prozess anzufassen.
     *
     * @param directory Service-Ordner
     * @return {@code true}, wenn eine Datei entfernt wurde
     */
    public static boolean delete(Path directory) {
        try {
            return Files.deleteIfExists(fileIn(directory));
        } catch (IOException e) {
            log.warn("PID-Eintrag in {} konnte nicht entfernt werden: {}", directory, e.getMessage());
            return false;
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Feld fehlt: " + key);
        }
        return value;
    }
}
