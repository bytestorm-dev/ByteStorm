package dev.bytestorm.launcher;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import dev.bytestorm.cli.Theme;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Prüft die Zeilen, die während der interaktiven Konsole über JLine laufen: Sie
 * tragen Uhrzeit und Level wie der Konsolen-Appender und keinen Logger-Namen.
 */
class ConsoleLogBridgeTest {

    @Test
    void formatsTimeLevelAndMessage() {
        String line = Theme.stripAnsi(ConsoleLogBridge.format(event(Level.WARN, "Minimum 1 nicht erreichbar")));

        assertEquals("16:42:01|WARN  Minimum 1 nicht erreichbar", line);
    }

    @Test
    void keepsErrorReadableAndDropsTheLoggerName() {
        String line = Theme.stripAnsi(ConsoleLogBridge.format(event(Level.ERROR, "Healthcheck mongo fehlgeschlagen")));

        assertEquals("16:42:01|ERROR Healthcheck mongo fehlgeschlagen", line);
        assertFalse(line.contains("ScalingManager"), line);
    }

    private static LoggingEvent event(Level level, String message) {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("dev.bytestorm.controller.scaling.ScalingManager");
        LoggingEvent event = new LoggingEvent("dev.bytestorm.controller.scaling.ScalingManager",
                logger, level, message, null, null);
        event.setTimeStamp(LocalDateTime.of(2026, 11, 2, 16, 42, 1)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        return event;
    }
}
