package dev.bytestorm.node.lifecycle;

import java.io.IOException;

/**
 * Baut den Service-Ordner vor dem Start auf (Konzept §10, §12).
 *
 * <p>Die Template-Implementierung folgt in Phase 5; der Lifecycle kennt nur
 * diese drei Schritte. {@code prepare} läuft bei jedem Start, {@code reapply}
 * nur auf ausdrücklichen Befehl, {@code cleanup} nach dem Stop eines dynamischen
 * Services.
 */
public interface ServicePreparer {

    /**
     * Legt Ordner an und kopiert gegebenenfalls Templates.
     *
     * @param definition Service
     * @throws IOException wenn der Ordner nicht aufgebaut werden kann
     */
    void prepare(ServiceDefinition definition) throws IOException;

    /**
     * Legt Ordner an und kopiert gegebenenfalls Templates, mit dem bereits
     * reservierten Port für {@code %PORT%}.
     *
     * @param definition Service
     * @param port       reservierter Port oder {@code 0}, wenn noch keiner vergeben ist
     * @throws IOException wenn der Ordner nicht aufgebaut werden kann
     */
    default void prepare(ServiceDefinition definition, int port) throws IOException {
        prepare(definition);
    }

    /**
     * Wendet Templates erneut an, ohne Nicht-Template-Dateien zu überschreiben.
     *
     * @param definition Service
     * @throws IOException wenn das erneute Anwenden fehlschlägt
     */
    default void reapply(ServiceDefinition definition) throws IOException {
        // Phase 5: nur Template-Dateien überschreiben.
    }

    /**
     * Wendet Templates erneut an und setzt {@code %PORT%} auf den zuletzt
     * vergebenen Port. Ohne ihn bliebe der Platzhalter stehen, weil ein gestoppter
     * Service keinen Port mehr hält.
     *
     * @param definition Service
     * @param port       zuletzt vergebener Port oder {@code 0}, wenn keiner bekannt ist
     * @throws IOException wenn das erneute Anwenden fehlschlägt
     */
    default void reapply(ServiceDefinition definition, int port) throws IOException {
        reapply(definition);
    }

    /**
     * Entfernt den Service-Ordner eines dynamischen Services.
     *
     * @param definition Service
     * @throws IOException wenn nicht vollständig aufgeräumt werden kann
     */
    default void cleanup(ServiceDefinition definition) throws IOException {
        // Phase 5: dynamische Ordner löschen.
    }
}
