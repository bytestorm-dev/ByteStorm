package dev.bytestorm.cli;

import dev.bytestorm.core.update.CloudVersion;
import dev.bytestorm.core.update.Updater;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Kommandos rund um Updates der Cloud (§17).
 *
 * <p>Die Quelle ist der {@link Updater}; die CLI listet Releases, vergleicht die
 * laufende Version ({@link CloudVersion}) und spielt ein Release ein. Die eigene
 * Version kommt austauschbar herein, damit Tests ohne Jar-Manifest laufen.
 *
 * <p>Der Download des Artefakts meldet seinen Fortschritt an einen
 * {@link Updater.ProgressListener}: im Terminal zeichnet {@link UpdateProgress}
 * einen Balken, Tests reichen einen stillen Empfänger herein.
 */
public final class UpdaterCommandLine {

    /** Hilfe für {@code updater help}. */
    public static final String USAGE = """
            updater versions [--all]   verfügbare Releases dieses Projekts
            updater check              prüfen, ob ein neueres Release vorliegt
            updater update [version]   neuestes (oder genanntes) Release einspielen
            updater repository         Quelle und laufende Version zeigen
            """;

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    /** Liefert die Version der laufenden Cloud. */
    @FunctionalInterface
    public interface VersionProvider {

        /**
         * @return die laufende Version, leer wenn unbekannt
         */
        Optional<String> current();
    }

    private final Updater updater;
    private final VersionProvider version;
    private final Updater.ProgressListener progress;

    /**
     * @param updater Updater mit der konfigurierten Quelle
     */
    public UpdaterCommandLine(Updater updater) {
        this(updater, CloudVersion::current);
    }

    /**
     * @param updater Updater mit der konfigurierten Quelle
     * @param version Quelle der laufenden Version
     */
    public UpdaterCommandLine(Updater updater, VersionProvider version) {
        this(updater, version, UpdateProgress.console());
    }

    /**
     * @param updater  Updater mit der konfigurierten Quelle
     * @param version  Quelle der laufenden Version
     * @param progress Empfänger des Download-Fortschritts
     */
    public UpdaterCommandLine(Updater updater, VersionProvider version,
                              Updater.ProgressListener progress) {
        this.updater = Objects.requireNonNull(updater, "updater");
        this.version = Objects.requireNonNull(version, "version");
        this.progress = Objects.requireNonNull(progress, "progress");
    }

    /**
     * Führt ein Updater-Kommando aus.
     *
     * @param tokens Kommandozeile, z. B. {@code updater update}
     * @return Ausgabe für das Terminal
     */
    public String execute(String[] tokens) {
        String command = tokens.length > 1 ? tokens[1] : "";
        String argument = tokens.length > 2 ? tokens[2] : null;
        return switch (command.toLowerCase(Locale.ROOT)) {
            case "", "help", "-h", "--help" -> Theme.help(USAGE.stripTrailing());
            case "versions", "list", "available" -> versions(argument);
            case "check" -> check();
            case "update", "install" -> update(argument);
            case "repository", "source" -> Theme.field("Repository", updater.repository())
                    + System.lineSeparator() + Theme.field("Laufende Version", currentVersion());
            default -> Theme.warn("Unbekanntes Updater-Kommando: " + tokens[1] + " (updater help)");
        };
    }

    private String versions(String argument) {
        boolean includePrereleases = argument != null
                && (argument.equalsIgnoreCase("--all") || argument.equalsIgnoreCase("all"));
        try {
            List<Updater.Release> releases = updater.releases();
            if (releases.isEmpty()) {
                return Theme.warn("Keine Releases in " + updater.repository() + " gefunden.");
            }
            Table table = Table.of("Tag", "Art", "Veröffentlicht", "Artefakt");
            int shown = 0;
            for (Updater.Release release : releases) {
                if (release.prerelease() && !includePrereleases) {
                    continue;
                }
                table.row(Theme.bold(release.tag()),
                        release.prerelease() ? Theme.yellow("Beta") : Theme.green("Release"),
                        date(release.publishedAt()),
                        release.installable() ? release.assetName() : Theme.dim("—"));
                shown++;
            }
            if (shown == 0) {
                return Theme.warn("Nur Beta-Releases in " + updater.repository()
                        + " — 'updater versions --all' zeigt sie.");
            }
            String text = table.render();
            if (!includePrereleases) {
                text += System.lineSeparator()
                        + Theme.dim("Beta-Releases ausblenden/zeigen: 'updater versions --all'");
            }
            return text;
        } catch (RuntimeException e) {
            return Theme.fail("Fehler: " + messageOf(e));
        }
    }

    private String check() {
        try {
            Optional<Updater.Release> newest = updater.latest();
            if (newest.isEmpty()) {
                return Theme.warn("Kein Release in " + updater.repository() + " gefunden.");
            }
            Updater.Release release = newest.get();
            Optional<String> running = version.current();
            if (running.isPresent() && !CloudVersion.isNewer(release.version(), running.get())) {
                return Theme.ok("Aktuell: " + running.get());
            }
            String own = running.orElse(CloudVersion.UNKNOWN);
            String hint = running.isEmpty()
                    ? " (eigene Version unbekannt — läuft die Cloud nicht aus dem Jar?)"
                    : "";
            return Theme.warn("Update verfügbar: " + own + " " + Theme.arrow() + " " + release.tag() + hint)
                    + System.lineSeparator()
                    + Theme.dim("Einspielen mit 'updater update'.");
        } catch (RuntimeException e) {
            return Theme.fail("Fehler: " + messageOf(e));
        }
    }

    private String update(String argument) {
        try {
            Optional<Updater.Release> release = argument == null || argument.isBlank()
                    ? updater.latest()
                    : updater.byVersion(argument);
            if (release.isEmpty()) {
                String wanted = argument == null || argument.isBlank() ? "ein Release" : argument;
                return Theme.warn("Kein passendes Release gefunden: " + wanted + " (updater versions)");
            }
            Updater.Release found = release.get();
            if (!found.installable()) {
                return Theme.warn("Release " + found.tag() + " hat kein Zip-Artefakt — nichts eingespielt.");
            }
            int files = updater.install(found, progress);
            return Theme.ok("Update eingespielt: " + found.tag() + " (" + files + " Dateien, vorherige"
                    + " Fassung unter update/backup/)") + System.lineSeparator()
                    + Theme.warn("Neustart nötig — die laufende Cloud arbeitet weiter mit den "
                    + "geladenen Klassen.");
        } catch (RuntimeException e) {
            return Theme.fail("Fehler: " + messageOf(e));
        }
    }

    private String currentVersion() {
        try {
            return version.current().orElse(CloudVersion.UNKNOWN);
        } catch (RuntimeException e) {
            return CloudVersion.UNKNOWN;
        }
    }

    private static String date(Instant instant) {
        return instant == null || Instant.EPOCH.equals(instant) ? "—" : DATE.format(instant);
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
