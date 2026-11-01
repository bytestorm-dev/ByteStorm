package dev.bytestorm.core.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import lombok.experimental.UtilityClass;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Programmatisches Logging-Setup (§3.6): Konsole plus rotierende Datei
 * {@code logs/cloud.log}.
 *
 * <p>Nach außen spricht diese Klasse ausschließlich SLF4J — Anwendungscode
 * loggt über {@code org.slf4j.Logger} und übergibt Level als
 * {@link Level}. Logback ist lediglich die Implementierung dahinter und
 * bleibt ein Detail dieser Klasse; es wird nur programmatisch konfiguriert,
 * weil das Zielverzeichnis erst zur Laufzeit feststeht.
 *
 * <p>Rotation erfolgt täglich bzw. bei 50 MB; Archive werden gzip-komprimiert
 * und nach 14 Tagen bzw. 1 GB Gesamtgröße entfernt.
 *
 * <p>Mehrfache Aufrufe sind idempotent und legen keinen zweiten Appender an.
 */
@UtilityClass
public final class Logging {

    /** Verzeichnis für Logdateien, relativ zum Cloud-Verzeichnis. */
    public static final String LOG_DIRECTORY = "logs";

    /** Name der aktiven Logdatei. */
    public static final String LOG_FILE = "cloud.log";

    /** Datei: vollständig, mit Datum und Thread — dort wird gesucht, nicht gelesen. */
    private static final String FILE_PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n";

    /**
     * Konsole: nur Level, Logger und Text. Wer vor dem Terminal sitzt, braucht
     * weder Datum noch Uhrzeit noch Thread-Namen je Zeile — all das steht in der
     * Datei, die man bei Bedarf öffnet.
     */
    private static final String CONSOLE_PATTERN =
            "%-5level %logger{20} - %msg%n";

    /** Wie {@link #CONSOLE_PATTERN}, aber mit farbigem Level. */
    private static final String CONSOLE_PATTERN_COLORED =
            "%highlight(%-5level) %logger{20} - %msg%n";

    private static final String CONFIGURED_KEY = "bytestorm.logging.file";

    private static final String ARCHIVE_PATTERN = "cloud.%d{yyyy-MM-dd}.%i.log.gz";

    private static final String MAX_FILE_SIZE = "50MB";

    private static final String TOTAL_SIZE_CAP = "1GB";

    private static final int MAX_HISTORY_DAYS = 14;

    /**
     * Fremd-Logger, die im Normalbetrieb nur Warnungen melden. Mongo-Treiber und
     * Redisson schreiben sonst bei jedem Start Verbindungs- und Client-Details
     * auf INFO und machen {@code logs/cloud.log} unlesbar.
     */
    private static final Map<String, ch.qos.logback.classic.Level> QUIET_LOGGERS = Map.of(
            "org.mongodb.driver", ch.qos.logback.classic.Level.WARN,
            "org.redisson", ch.qos.logback.classic.Level.WARN);

    /**
     * Konfiguriert den globalen Logger mit {@link Level#INFO}.
     *
     * @param baseDirectory Cloud-Verzeichnis, unter dem {@code logs/} liegt
     * @return Pfad der aktiven Logdatei
     */
    public static Path configure(Path baseDirectory) {
        return configure(baseDirectory, Level.INFO);
    }

    /**
     * Konfiguriert den globalen Logger.
     *
     * @param baseDirectory Cloud-Verzeichnis, unter dem {@code logs/} liegt
     * @param rootLevel     Level des Root-Loggers, als SLF4J-Level
     * @return Pfad der aktiven Logdatei
     * @throws IllegalStateException wenn Logback nicht der aktive SLF4J-Provider ist
     */
    public static Path configure(Path baseDirectory, Level rootLevel) {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            throw new IllegalStateException("Logback ist nicht der aktive SLF4J-Provider — Logging kann nicht konfiguriert werden");
        }
        return configure(context, baseDirectory, rootLevel);
    }

    /**
     * Konfiguriert einen bestimmten Logback-Kontext. Paketprivat, damit die
     * Logback-Abhängigkeit nicht nach außen sichtbar wird; Tests können so den
     * globalen Logger unverändert lassen.
     *
     * @param context       Logback-Kontext
     * @param baseDirectory Cloud-Verzeichnis, unter dem {@code logs/} liegt
     * @param rootLevel     Level des Root-Loggers, als SLF4J-Level
     * @return Pfad der aktiven Logdatei
     */
    static Path configure(LoggerContext context, Path baseDirectory, Level rootLevel) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(baseDirectory, "baseDirectory");
        Objects.requireNonNull(rootLevel, "rootLevel");

        synchronized (context) {
            if (context.getObject(CONFIGURED_KEY) instanceof Path existing) {
                return existing;
            }

            Path logDirectory = baseDirectory.resolve(LOG_DIRECTORY);
            try {
                Files.createDirectories(logDirectory);
            } catch (IOException e) {
                throw new UncheckedIOException("Log-Verzeichnis nicht anlegbar: " + logDirectory, e);
            }
            Path logFile = logDirectory.resolve(LOG_FILE);

            context.reset();

            // Ein programmatisch erzeugter LoggerContext hat keinen MDC-Adapter;
            // Logback greift beim Schreiben eines Events aber unbedingt darauf zu.
            if (context.getMDCAdapter() == null) {
                context.setMDCAdapter(new LogbackMDCAdapter());
            }

            ConsoleAppender<ILoggingEvent> console = new ConsoleAppender<>();
            console.setContext(context);
            console.setName("bytestorm-console");
            console.setEncoder(encoder(context,
                    ansiConsole() ? CONSOLE_PATTERN_COLORED : CONSOLE_PATTERN));
            console.start();

            RollingFileAppender<ILoggingEvent> rolling = new RollingFileAppender<>();
            rolling.setContext(context);
            rolling.setName("bytestorm-file");
            rolling.setFile(logFile.toString());
            rolling.setEncoder(encoder(context, FILE_PATTERN));

            SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy = new SizeAndTimeBasedRollingPolicy<>();
            policy.setContext(context);
            policy.setParent(rolling);
            policy.setFileNamePattern(logDirectory.resolve(ARCHIVE_PATTERN).toString());
            policy.setMaxFileSize(FileSize.valueOf(MAX_FILE_SIZE));
            policy.setMaxHistory(MAX_HISTORY_DAYS);
            policy.setTotalSizeCap(FileSize.valueOf(TOTAL_SIZE_CAP));
            policy.start();

            rolling.setRollingPolicy(policy);
            rolling.start();

            Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            root.setLevel(toLogback(rootLevel));
            root.addAppender(console);
            root.addAppender(rolling);
            QUIET_LOGGERS.forEach((name, level) -> context.getLogger(name).setLevel(level));

            context.putObject(CONFIGURED_KEY, logFile);
            return logFile;
        }
    }

    private static ch.qos.logback.classic.Level toLogback(Level level) {
        return switch (level) {
            case TRACE -> ch.qos.logback.classic.Level.TRACE;
            case DEBUG -> ch.qos.logback.classic.Level.DEBUG;
            case INFO -> ch.qos.logback.classic.Level.INFO;
            case WARN -> ch.qos.logback.classic.Level.WARN;
            case ERROR -> ch.qos.logback.classic.Level.ERROR;
        };
    }

    /**
     * Entscheidet, ob die Konsole ANSI-Farben bekommt. Die Regeln sind bewusst
     * dieselben wie in der CLI: {@code -Dbytestorm.color=never} und
     * {@code NO_COLOR} verbieten Farben, {@code always} erzwingt sie, ein
     * Dumb-Terminal bleibt grau. Ist {@code TERM} gar nicht gesetzt (Dienst,
     * Pipe), bleibt die Ausgabe reiner Text.
     *
     * @return {@code true}, wenn das Level eingefärbt werden darf
     */
    static boolean ansiConsole() {
        String color = System.getProperty("bytestorm.color");
        if (color != null && color.equalsIgnoreCase("never")) {
            return false;
        }
        if (color != null && color.equalsIgnoreCase("always")) {
            return true;
        }
        String noColor = System.getenv("NO_COLOR");
        if (noColor != null && !noColor.isBlank()) {
            return false;
        }
        String term = System.getenv("TERM");
        if (term == null || term.isBlank() || term.equalsIgnoreCase("dumb")) {
            return false;
        }
        return true;
    }

    private static PatternLayoutEncoder encoder(LoggerContext context, String pattern) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(pattern);
        encoder.start();
        return encoder;
    }
}
