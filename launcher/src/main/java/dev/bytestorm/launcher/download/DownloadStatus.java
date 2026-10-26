package dev.bytestorm.launcher.download;

import java.util.Objects;

/**
 * Fortschritt einer abgeschlossenen Abhängigkeit.
 *
 * @param completed  Anzahl der fertigen Abhängigkeiten, beginnend bei 1
 * @param total      Gesamtzahl der Abhängigkeiten des Laufs
 * @param dependency die gerade fertig gewordene Abhängigkeit
 * @param downloaded {@code true}, wenn sie geladen wurde; {@code false}, wenn die
 *                   vorhandene Datei wiederverwendet wurde
 */
public record DownloadStatus(int completed, int total, Dependency dependency, boolean downloaded) {

    /**
     * @param completed  fertige Abhängigkeiten
     * @param total      Gesamtzahl
     * @param dependency Abhängigkeit
     * @param downloaded geladen oder wiederverwendet
     */
    public DownloadStatus {
        Objects.requireNonNull(dependency, "dependency");
        if (total < 1) {
            throw new IllegalArgumentException("total muss mindestens 1 sein: " + total);
        }
        if (completed < 1 || completed > total) {
            throw new IllegalArgumentException("completed muss zwischen 1 und " + total + " liegen: " + completed);
        }
    }

    /**
     * @return {@code true}, wenn dies der letzte Eintrag des Laufs ist
     */
    public boolean finished() {
        return completed == total;
    }
}
