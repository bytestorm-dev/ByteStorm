package dev.bytestorm.core.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft das Logging-Setup über den öffentlichen SLF4J-Pfad: Datei, Rotation und
 * Level werden über Logback-Kontexte geprüft, ohne den globalen Logger zu stören.
 */
class LoggingTest {

    @TempDir
    Path temp;

    @Test
    void writesConsoleAndFileAppenders() throws IOException {
        LoggerContext context = new LoggerContext();

        Path file = Logging.configure(context, temp, Level.DEBUG);

        assertEquals(temp.resolve(Logging.LOG_DIRECTORY).resolve(Logging.LOG_FILE), file);
        assertEquals(List.of("bytestorm-console", "bytestorm-file"), appenderNames(context));
        context.getLogger("bytestorm.logging").info("Hallo aus der Cloud");

        String content = Files.readString(file);
        assertTrue(content.contains("Hallo aus der Cloud"), content);
        assertTrue(content.contains("INFO"), content);
        assertTrue(content.contains("bytestorm.logging"), content);
    }

    @Test
    void respectsTheSlf4jLevel() throws IOException {
        LoggerContext context = new LoggerContext();
        Path file = Logging.configure(context, temp, Level.WARN);

        Logger logger = context.getLogger("bytestorm.level");
        logger.info("unsichtbar");
        logger.warn("sichtbar");

        String content = Files.readString(file);
        assertFalse(content.contains("unsichtbar"), content);
        assertTrue(content.contains("sichtbar"), content);
    }

    @Test
    void quietsMongoAndRedissonLoggers() throws IOException {
        LoggerContext context = new LoggerContext();
        Path file = Logging.configure(context, temp, Level.INFO);

        context.getLogger("org.mongodb.driver.client").info("mongo-details");
        context.getLogger("org.redisson.Version").info("redisson-details");
        context.getLogger("org.redisson.Version").warn("redisson-warnung");
        context.getLogger("bytestorm.own").info("eigene Zeile");

        String content = Files.readString(file);
        assertFalse(content.contains("mongo-details"), content);
        assertFalse(content.contains("redisson-details"), content);
        assertTrue(content.contains("redisson-warnung"), content);
        assertTrue(content.contains("eigene Zeile"), content);
        assertEquals(ch.qos.logback.classic.Level.WARN, context.getLogger("org.mongodb.driver").getLevel());
        assertEquals(ch.qos.logback.classic.Level.WARN, context.getLogger("org.redisson").getLevel());
    }

    @Test
    void isIdempotentAndKeepsTheFirstConfiguration() {
        LoggerContext context = new LoggerContext();

        Path first = Logging.configure(context, temp, Level.INFO);
        Path second = Logging.configure(context, temp.resolve("other"), Level.ERROR);

        assertEquals(first, second);
        assertEquals(List.of("bytestorm-console", "bytestorm-file"), appenderNames(context));
        assertEquals(ch.qos.logback.classic.Level.INFO, context.getLogger(Logger.ROOT_LOGGER_NAME).getLevel());
    }

    @Test
    void configuresTheGlobalSlf4jProvider() {
        assertTrue(LoggerFactory.getILoggerFactory() instanceof LoggerContext,
                "Logback muss der aktive SLF4J-Provider sein");

        Path file = Logging.configure(temp, Level.INFO);

        assertEquals(temp.resolve(Logging.LOG_DIRECTORY).resolve(Logging.LOG_FILE), file);
    }

    @Test
    void consoleShowsShortTimeAndColorsTheLevel() {
        LoggerContext context = new LoggerContext();
        // Farbwahl fest vorgeben: Ein CI-Runner hat kein TERM und bekäme sonst
        // still das graue Layout, obwohl dieser Test das farbige meint.
        Logging.configure(context, temp, Level.INFO, true);

        String pattern = consolePattern(context);

        assertTrue(pattern.startsWith("%d{HH:mm:ss}|"), "die Uhrzeit steht vor dem Level: " + pattern);
        assertTrue(pattern.contains("%highlight(%-5level)"), pattern);
        assertFalse(pattern.contains("%logger"), "die Konsole zeigt keinen Logger-Namen: " + pattern);

        // Ohne diese Prüfung fiele ein unbekanntes %highlight erst im Betrieb auf:
        // Logback schreibt das Wort dann als Text in jede Zeile.
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern("%highlight(%-5level)");
        layout.start();
        LoggingEvent event = new LoggingEvent("dev.bytestorm.test", context.getLogger("test"),
                ch.qos.logback.classic.Level.ERROR, "kaputt", null, null);
        String rendered = layout.doLayout(event);
        assertFalse(rendered.contains("%highlight"), rendered);
        assertTrue(rendered.contains("ERROR"), rendered);
    }

    @Test
    void consoleStaysPlainWhenColorsAreOff() {
        LoggerContext context = new LoggerContext();
        Logging.configure(context, temp, Level.INFO, false);

        String pattern = consolePattern(context);

        assertTrue(pattern.startsWith("%d{HH:mm:ss}|%-5level "), pattern);
        assertFalse(pattern.contains("%highlight"), "ohne Farben kein Highlight: " + pattern);
        assertFalse(pattern.contains("%logger"), "die Konsole zeigt keinen Logger-Namen: " + pattern);
    }

    private static String consolePattern(LoggerContext context) {
        var appenders = context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders();
        while (appenders.hasNext()) {
            Appender<ILoggingEvent> appender = appenders.next();
            if ("bytestorm-console".equals(appender.getName())
                    && appender instanceof ch.qos.logback.core.OutputStreamAppender<?> stream
                    && stream.getEncoder() instanceof PatternLayoutEncoder encoder) {
                return encoder.getPattern();
            }
        }
        throw new AssertionError("Konsolen-Appender fehlt");
    }

    private static List<String> appenderNames(LoggerContext context) {
        List<String> names = new ArrayList<>();
        context.getLogger(Logger.ROOT_LOGGER_NAME)
                .iteratorForAppenders()
                .forEachRemaining(appender -> names.add(appender.getName()));
        return names;
    }
}
