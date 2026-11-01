package dev.bytestorm.launcher;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import dev.bytestorm.cli.Theme;
import org.slf4j.LoggerFactory;

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
 * ({@code HH:mm:ss LEVEL Logger - Text}) und ohne Thread-Namen, den man im
 * Terminal nicht braucht. Die Datei {@code logs/cloud.log} bleibt unberührt und
 * behält das ausführliche Format. Nach dem Schließen hängt wieder der
 * ursprüngliche Appender am Root-Logger, sodass Shutdown-Meldungen normal
 * erscheinen.
 */
public final class ConsoleLogBridge implements AutoCloseable {

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
            case "ERROR" -> Theme.bold(Theme.red("ERR "));
            case "WARN" -> Theme.yellow("WARN");
            case "DEBUG", "TRACE" -> Theme.dim(pad(levelName));
            default -> Theme.green("INFO");
        };
        String logger = Theme.dim(shortLogger(event.getLoggerName()));
        return "  " + level + " " + logger + " " + event.getFormattedMessage();
    }

    /**
     * @param level Levelname
     * @return der Name auf vier Zeichen aufgefüllt, damit die Spalte steht
     */
    private static String pad(String level) {
        return level.length() >= 4 ? level.substring(0, 4) : level + " ".repeat(4 - level.length());
    }

    /**
     * Loggernamen im Terminal kurz halten: Der letzte Abschnitt reicht, weil die
     * Zeile ohnehin nur den Ablauf begleitet.
     */
    private static String shortLogger(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        String simple = dot < 0 ? name : name.substring(dot + 1);
        return "[" + simple + "]";
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
