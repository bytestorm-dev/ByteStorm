package dev.bytestorm.cli;

import java.util.List;

/**
 * Prüfungen für das Kommando {@code doctor} (§17).
 *
 * <p>Die CLI kennt nur diese Schnittstelle; die Prüfungen selbst brauchen
 * Verzeichnislayout, Konfiguration und Datenbankverbindungen und liegen deshalb
 * im Launcher. So bleibt {@link ServiceCommandLine} ohne diese Abhängigkeiten
 * testbar.
 */
public interface Diagnostics {

    /**
     * Gewicht eines Ergebnisses. {@code ERROR} heißt: In diesem Zustand
     * funktioniert die Cloud oder ihr Netzwerkverbund nicht wie erwartet.
     */
    enum Severity {
        OK,
        WARNING,
        ERROR
    }

    /**
     * @param severity Schwere
     * @param area     geprüfter Bereich, z. B. {@code Ports}
     * @param detail   Ergebnis oder Abweichung im Klartext
     */
    record Finding(Severity severity, String area, String detail) {

        /**
         * Ersetzt fehlende Werte, damit die Ausgabe nicht an einem {@code null}
         * scheitert.
         */
        public Finding {
            severity = severity == null ? Severity.ERROR : severity;
            area = area == null ? "" : area;
            detail = detail == null ? "" : detail;
        }
    }

    /**
     * @return Prüfergebnisse in Anzeigereihenfolge
     */
    List<Finding> run();
}
