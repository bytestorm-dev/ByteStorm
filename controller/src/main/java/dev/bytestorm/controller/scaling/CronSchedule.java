package dev.bytestorm.controller.scaling;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Fünf-Feld-Cron-Ausdruck für Zeitplan-Scaling (§11).
 *
 * <p>Unterstützt werden {@code *}, {@code ?}, einzelne Werte, Listen
 * ({@code 1,2,3}), Bereiche ({@code 18-23}) und Schritte ({@code *}{@code /5}).
 * Die Felder sind Minute, Stunde, Tag im Monat, Monat und Wochentag
 * (0/7 = Sonntag). Ist sowohl Tag im Monat als auch Wochentag eingeschränkt,
 * genügt wie bei klassischem Cron ein Treffer.
 *
 * <p>Der Ausdruck beschreibt einen Zeitpunkt, keinen Zeitraum. Für ein Fenster
 * von 18 bis 23 Uhr ist deshalb {@code * 18-23 * * *} zu verwenden.
 *
 * <p>Öffentlich, damit die Validierung im {@code GroupManager} denselben Parser
 * nutzt wie der ScalingManager und keine zweite Auslegung entsteht.
 */
public final class CronSchedule {

    private final Set<Integer> minutes;
    private final Set<Integer> hours;
    private final Set<Integer> daysOfMonth;
    private final Set<Integer> months;
    private final Set<Integer> daysOfWeek;
    private final boolean dayOfMonthWildcard;
    private final boolean dayOfWeekWildcard;

    private CronSchedule(Set<Integer> minutes,
                         Set<Integer> hours,
                         Set<Integer> daysOfMonth,
                         Set<Integer> months,
                         Set<Integer> daysOfWeek,
                         boolean dayOfMonthWildcard,
                         boolean dayOfWeekWildcard) {
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.dayOfMonthWildcard = dayOfMonthWildcard;
        this.dayOfWeekWildcard = dayOfWeekWildcard;
    }

    /**
     * @param expression fünf durch Leerzeichen getrennte Felder
     * @return der geparste Ausdruck
     * @throws IllegalArgumentException wenn Anzahl, Werte oder Bereiche ungültig sind
     */
    public static CronSchedule parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Cron-Ausdruck fehlt");
        }
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("Cron-Ausdruck braucht 5 Felder: '" + expression + "'");
        }
        boolean dayOfMonthWildcard = isWildcard(fields[2]);
        boolean dayOfWeekWildcard = isWildcard(fields[4]);
        return new CronSchedule(
                parseField(fields[0], 0, 59, "Minute"),
                parseField(fields[1], 0, 23, "Stunde"),
                parseField(fields[2], 1, 31, "Tag im Monat"),
                parseField(fields[3], 1, 12, "Monat"),
                parseField(fields[4], 0, 7, "Wochentag"),
                dayOfMonthWildcard,
                dayOfWeekWildcard);
    }

    /**
     * @param time Zeitpunkt in der Zone des Zeitplans
     * @return {@code true}, wenn der Ausdruck diese Minute trifft
     */
    boolean matches(ZonedDateTime time) {
        if (!minutes.contains(time.getMinute()) || !hours.contains(time.getHour())
                || !months.contains(time.getMonthValue())) {
            return false;
        }
        boolean dayOfMonth = daysOfMonth.contains(time.getDayOfMonth());
        boolean dayOfWeek = daysOfWeek.contains(time.getDayOfWeek().getValue() % 7);
        if (dayOfMonthWildcard && dayOfWeekWildcard) {
            return true;
        }
        if (dayOfMonthWildcard) {
            return dayOfWeek;
        }
        if (dayOfWeekWildcard) {
            return dayOfMonth;
        }
        return dayOfMonth || dayOfWeek;
    }

    private static boolean isWildcard(String field) {
        return field.equals("*") || field.equals("?");
    }

    private static Set<Integer> parseField(String field, int min, int max, String name) {
        Set<Integer> values = new HashSet<>();
        for (String part : field.split(",", -1)) {
            if (part.isBlank()) {
                throw new IllegalArgumentException(name + " enthält ein leeres Feld: '" + field + "'");
            }
            parsePart(part, min, max, name, values);
        }
        return Set.copyOf(values);
    }

    private static void parsePart(String part, int min, int max, String name, Set<Integer> values) {
        String range = part;
        int step = 1;
        int slash = part.indexOf('/');
        if (slash >= 0) {
            range = part.substring(0, slash);
            step = parseInt(part.substring(slash + 1), name);
            if (step < 1) {
                throw new IllegalArgumentException(name + ": Schritt muss positiv sein: '" + part + "'");
            }
        }

        int start;
        int end;
        if (range.equals("*") || range.equals("?")) {
            start = min;
            end = max;
        } else {
            int dash = range.indexOf('-');
            if (dash > 0) {
                start = parseInt(range.substring(0, dash), name);
                end = parseInt(range.substring(dash + 1), name);
            } else {
                start = parseInt(range, name);
                end = slash >= 0 ? max : start;
            }
        }
        if (start < min || end > max || start > end) {
            throw new IllegalArgumentException(name + ": Bereich '" + part + "' liegt nicht in "
                    + min + "-" + max);
        }
        for (int value = start; value <= end; value += step) {
            values.add(name.equals("Wochentag") && value == 7 ? 0 : value);
        }
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + ": keine Zahl '" + value + "'", e);
        }
    }
}
