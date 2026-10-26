package dev.bytestorm.core.update;

/**
 * Version der laufenden Cloud (§17).
 *
 * <p>Die Version steht im Manifest des {@code core}-Jars
 * ({@code Implementation-Version}), das der Build aus {@code gradle.properties}
 * setzt. Läuft die Cloud aus einem Verzeichnis statt aus einem Jar — etwa in
 * Tests —, ist sie unbekannt; dann liefert {@link #current()} nichts und der
 * Updater meldet den Vergleich als „unbekannt“, statt etwas zu behaupten.
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
        String trimmed = version.trim();
        return java.util.Optional.of(trimmed.startsWith("v") ? trimmed.substring(1) : trimmed);
    }

    /**
     * @return die Version für Ausgaben, nie leer
     */
    public static String currentOrUnknown() {
        return current().orElse(UNKNOWN);
    }
}
