package dev.bytestorm.controller.scaling;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CronScheduleTest {

    @Test
    void matchesAHourWindow() {
        CronSchedule cron = CronSchedule.parse("* 18-23 * * *");

        assertFalse(cron.matches(at("2026-10-23T17:59:00Z")));
        assertTrue(cron.matches(at("2026-10-23T18:00:00Z")));
        assertTrue(cron.matches(at("2026-10-23T23:59:00Z")));
        assertFalse(cron.matches(at("2026-10-24T00:00:00Z")));
    }

    @Test
    void supportsStepsAndLists() {
        CronSchedule cron = CronSchedule.parse("*/15 8,20 * * *");

        assertTrue(cron.matches(at("2026-10-23T08:00:00Z")));
        assertTrue(cron.matches(at("2026-10-23T08:15:00Z")));
        assertTrue(cron.matches(at("2026-10-23T20:45:00Z")));
        assertFalse(cron.matches(at("2026-10-23T08:07:00Z")));
        assertFalse(cron.matches(at("2026-10-23T19:15:00Z")));
    }

    @Test
    void matchesWeekdaysAndSundayAsSeven() {
        CronSchedule monday = CronSchedule.parse("0 12 * * 1");
        CronSchedule sunday = CronSchedule.parse("0 12 * * 7");

        assertTrue(monday.matches(at("2026-10-26T12:00:00Z")));
        assertFalse(monday.matches(at("2026-10-27T12:00:00Z")));
        assertTrue(sunday.matches(at("2026-10-25T12:00:00Z")));
    }

    @Test
    void rejectsInvalidExpressions() {
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("* * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("60 * * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("* 23-18 * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("*/0 * * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("x * * * *"));
    }

    private static ZonedDateTime at(String instant) {
        return ZonedDateTime.ofInstant(java.time.Instant.parse(instant), ZoneOffset.UTC);
    }
}
