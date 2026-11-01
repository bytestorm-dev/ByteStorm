package dev.bytestorm.launcher.update;

import dev.bytestorm.core.update.CloudVersion;
import dev.bytestorm.core.update.Updater;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Meldet beim Start, wenn ein neueres Release veröffentlicht ist (§17).
 *
 * <p>Die Prüfung läuft in einem virtuellen Thread: Sie darf den Start weder
 * verzögern noch verhindern. Ohne Netz, bei einem Rate-Limit oder einer
 * unbekannten eigenen Version bleibt sie still und schreibt nur ins Debug-Log.
 * Eingespielt wird nichts — der Hinweis nennt den Befehl {@code updater update},
 * damit der Betreiber den Neustart selbst bestimmt.
 */
@Slf4j
public final class UpdateNotifier {

    private UpdateNotifier() {
    }

    /**
     * Startet die Prüfung im Hintergrund.
     *
     * @param updater Updater mit der konfigurierten Quelle
     * @return der Thread, der die Prüfung ausführt
     */
    public static Thread checkInBackground(Updater updater) {
        return Thread.ofVirtual()
                .name("bytestorm-update-check")
                .start(() -> notice(updater, CloudVersion.current().orElse(null))
                        .ifPresent(log::warn));
    }

    /**
     * @param updater        Updater mit der konfigurierten Quelle
     * @param runningVersion laufende Version oder {@code null}, wenn unbekannt
     * @return der Hinweis für das Log, leer wenn kein Update vorliegt
     */
    static Optional<String> notice(Updater updater, String runningVersion) {
        if (runningVersion == null || runningVersion.isBlank()) {
            log.debug("Update-Prüfung übersprungen: eigene Version unbekannt");
            return Optional.empty();
        }
        try {
            Optional<Updater.Release> newer = updater.newerThan(runningVersion);
            if (newer.isEmpty()) {
                log.debug("Kein Update verfügbar (läuft {})", runningVersion);
                return Optional.empty();
            }
            return Optional.of(message(runningVersion, newer.get()));
        } catch (RuntimeException e) {
            // Ohne Netz oder bei Rate-Limit startet die Cloud trotzdem.
            log.debug("Update-Prüfung fehlgeschlagen: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * @param runningVersion laufende Version
     * @param release        neueres Release
     * @return Hinweis mit Tag, Einspielbefehl und Release-Seite
     */
    static String message(String runningVersion, Updater.Release release) {
        String hint = release.installable()
                ? " — einspielen mit 'updater update'"
                : " (Release ohne Zip-Artefakt)";
        String link = release.url() == null || release.url().isBlank() ? "" : " " + release.url();
        return "Update verfügbar: " + runningVersion + " → " + release.tag() + hint + link;
    }
}
