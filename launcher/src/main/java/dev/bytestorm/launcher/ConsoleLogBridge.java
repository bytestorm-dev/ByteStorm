package dev.bytestorm.launcher;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import dev.bytestorm.cli.Theme;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Leitet Konsolen-Logs während der interaktiven Konsole an JLine weiter.
 *
 * <p>Ohne diese Brücke schreiben Logback und JLine gleichzeitig auf das
 * Terminal: Ein Hintergrund-Thread (Service-Start, Shutdown) landet mitten in der
 * Eingabezeile, und der Prompt zerfällt zu
 * {@code Dashboard beendet.bytestorm · › shutdown16:57:27 INFO …}.
 *
 * <p>Die Brücke ersetzt für die Dauer der Konsole den Konsolen-Appender: Jede
 * Zeile geht über {@code printAbove} über der Eingabe vorbei, kurz formatiert
 * ({@code 16:42:01|INFO  Text}, genau wie der Konsolen-Appender von
 * {@code Logging}) und ohne Thread- oder Logger-Namen, die man im Terminal nicht
 * braucht. Die Datei {@code logs/cloud.log} bleibt unberührt und behält das
 * ausführliche Format. Nach dem Schließen hängt wieder der ursprüngliche
 * Appender am Root-Logger, sodass Shutdown-Meldungen normal erscheinen.
 */
public final class ConsoleLogBridge implements AutoCloseable {

    /** Uhrzeit der Konsole: Stunden, Minuten, Sekunden. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ch.qos.logback.classic.Logger root;
    private final Appender<ILoggingEvent> appender;
    private final Appender<ILoggingEvent> replaced;

    private ConsoleLogBridge(ch.qos.logback.classic.Logger root,
                              Appender<ILoggingEvent> appender,
                              Appender<ILoggingEvent> replaced) {
        this.root = root;
        this.appender = appender;
        this.replaced = replaced;
    }

    /**
     * Hängt die Brücke an den Root-Logger und entfernt dabei den bisherigen
     * Konsolen-Appender, damit keine Zeile doppelt erscheint.
     *
     * @param sink Empfänger für fertige Zeilen, üblicherweise
     *             {@code ServiceConsole::printAbove}
     * @return die Brücke; ohne Logback eine wirkungslose Instanz
     */
    public static ConsoleLogBridge attach(Consumer<String> sink) {
        Objects.requireNonNull(sink, "sink");
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return new ConsoleLogBridge(null, null, null);
        }
        ch.qos.logback.classic.Logger root =
                context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        Appender<ILoggingEvent> replaced = findConsole(root);
        Appender<ILoggingEvent> appender = new TerminalAppender(sink);
        appender.setContext(context);
        appender.setName("bytestorm-terminal");
        appender.start();
        if (replaced != null) {
            root.detachAppender(replaced);
        }
        root.addAppender(appender);
        return new ConsoleLogBridge(root, appender, replaced);
    }

    /**
     * @param event Logereignis
     * @return eine kurze Zeile für das Terminal, mit Farben
     */
    static String format(ILoggingEvent event) {
        // Über den Namen statt über den Enum-Typ: Logback und SLF4J liefern je nach
        // Version ihre eigene Level-Klasse, der Name bleibt stabil.
        String levelName = event.getLevel() == null ? "INFO" : event.getLevel().toString();
        String level = switch (levelName) {
            case "ERROR" -> Theme.bold(Theme.red(pad(levelName)));
            case "WARN" -> Theme.yellow(pad(levelName));
            case "DEBUG", "TRACE" -> Theme.dim(pad(levelName));
            default -> Theme.green(pad(levelName));
        };
        return time(event.getTimeStamp()) + Theme.dim("|") + level + " " + event.getFormattedMessage();
    }

    /**
     * @param timestamp Zeitpunkt des Ereignisses in Millisekunden
     * @return die Uhrzeit in der Zeitzone des Rechners
     */
    private static String time(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
                .toLocalTime().format(TIME);
    }

    /**
     * @param level Levelname
     * @return der Name auf fünf Zeichen aufgefüllt, damit die Spalte steht
     */
    private static String pad(String level) {
        return level.length() >= 5 ? level : level + " ".repeat(5 - level.length());
    }

    private static Appender<ILoggingEvent> findConsole(ch.qos.logback.classic.Logger root) {
        var appenders = root.iteratorForAppenders();
        while (appenders.hasNext()) {
            Appender<ILoggingEvent> appender = appenders.next();
            if ("bytestorm-console".equals(appender.getName())) {
                return appender;
            }
        }
        return null;
    }

    /**
     * Stellt den ursprünglichen Appender wieder her.
     */
    @Override
    public void close() {
        if (root == null || appender == null) {
            return;
        }
        root.detachAppender(appender);
        appender.stop();
        if (replaced != null) {
            root.addAppender(replaced);
        }
    }

    /**
     * Schreibt jede Logzeile in die Konsole; mehrzeilige Meldungen bleiben
     * mehrzeilig.
     */
    private static final class TerminalAppender extends AppenderBase<ILoggingEvent> {

        private final Consumer<String> sink;

        private TerminalAppender(Consumer<String> sink) {
            this.sink = sink;
        }

        @Override
        protected void append(ILoggingEvent event) {
            for (String line : format(event).split("\\R", -1)) {
                try {
                    sink.accept(line);
                } catch (RuntimeException e) {
                    // Ein geschlossenes Terminal darf das Logging nicht aufhalten.
                    return;
                }
            }
        }
    }
}
