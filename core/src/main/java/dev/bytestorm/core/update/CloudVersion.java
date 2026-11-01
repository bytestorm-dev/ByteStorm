package dev.bytestorm.core.update;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Version der laufenden Cloud (§17).
 *
 * <p>Die Version steht im Manifest des {@code core}-Jars
 * ({@code Implementation-Version}), das der Build aus {@code gradle.properties}
 * setzt. Läuft die Cloud aus einem Verzeichnis statt aus einem Jar — etwa in
 * Tests —, ist sie unbekannt; dann liefert {@link #current()} nichts und der
 * Updater meldet den Vergleich als „unbekannt“, statt etwas zu behaupten.
 *
 * <p>{@link #compare} vergleicht Versionen ohne Semver-Bibliothek: Zahlen werden
 * numerisch verglichen, Vorabkennungen wie {@code SNAPSHOT} oder {@code BETA}
 * sortieren vor der fertigen Version. So meldet die Prüfung beim Start nicht
 * „Update verfügbar“, wenn ein Entwicklungsstand neuer als das letzte Release ist.
 */
public final class CloudVersion {

    /** Platzhalter, wenn keine Version im Manifest steht. */
    public static final String UNKNOWN = "unbekannt";

    private CloudVersion() {
    }

    /**
     * @return die Version ohne führendes {@code v}, oder nichts, wenn sie fehlt
     */
    public static java.util.Optional<String> current() {
        Package type = CloudVersion.class.getPackage();
        String version = type == null ? null : type.getImplementationVersion();
        if (version == null || version.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(strip(version));
    }

    /**
     * @return die Version für Ausgaben, nie leer
     */
    public static String currentOrUnknown() {
        return current().orElse(UNKNOWN);
    }

    /**
     * Vergleicht zwei Versionen Abschnitt für Abschnitt.
     *
     * @param left  Version, z. B. {@code v1.2-SNAPSHOT}
     * @param right Version, z. B. {@code 1.1}
     * @return negativ, wenn {@code left} älter ist; {@code 0} bei Gleichstand; positiv, wenn neuer
     */
    public static int compare(String left, String right) {
        List<String> a = segments(left);
        List<String> b = segments(right);
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            String first = i < a.size() ? a.get(i) : "0";
            String second = i < b.size() ? b.get(i) : "0";
            int result = compareSegment(first, second);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    /**
     * @param candidate geprüfte Version, z. B. die des Releases
     * @param running   laufende Version
     * @return {@code true}, wenn {@code candidate} neuer als {@code running} ist
     */
    public static boolean isNewer(String candidate, String running) {
        return compare(candidate, running) > 0;
    }

    private static List<String> segments(String version) {
        String stripped = strip(version);
        if (stripped.isEmpty()) {
            return List.of("0");
        }
        return Arrays.stream(stripped.split("[._+-]"))
                .map(String::trim)
                .filter(segment -> !segment.isEmpty())
                .toList();
    }

    private static int compareSegment(String left, String right) {
        Long first = number(left);
        Long second = number(right);
        if (first != null && second != null) {
            return Long.compare(first, second);
        }
        if (first != null) {
            // Eine Zahl schlägt eine Vorabkennung: 1.0 ist neuer als 1.0-SNAPSHOT.
            return 1;
        }
        if (second != null) {
            return -1;
        }
        return Integer.compare(rank(left), rank(right));
    }

    private static int rank(String qualifier) {
        return switch (qualifier.toLowerCase(Locale.ROOT)) {
            case "snapshot" -> 0;
            case "alpha" -> 1;
            case "beta" -> 2;
            case "rc" -> 3;
            default -> 4;
        };
    }

    private static Long number(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String strip(String version) {
        String trimmed = version == null ? "" : version.trim();
        return trimmed.startsWith("v") || trimmed.startsWith("V") ? trimmed.substring(1) : trimmed;
    }
}
