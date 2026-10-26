package dev.bytestorm.api.template;

import dev.bytestorm.proto.TemplateInfo;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Template-Verwaltung. Inhalte liegen als Ordner in {@code templates/},
 * hier werden nur Metadaten und Aktionen angeboten (Konzept §10).
 */
public interface Templates {

    /**
     * @return Metadaten aller Templates
     */
    Collection<TemplateInfo> list();

    /**
     * @param name Templatename
     * @return Metadaten, falls das Template existiert
     */
    Optional<TemplateInfo> get(String name);

    /**
     * Legt einen leeren Template-Ordner an.
     *
     * @param name Templatename
     * @return Future, das nach dem Anlegen abgeschlossen ist
     */
    CompletableFuture<Void> create(String name);

    /**
     * Löscht ein Template samt Ordner.
     *
     * @param name Templatename
     * @return Future, das nach dem Löschen abgeschlossen ist
     */
    CompletableFuture<Void> delete(String name);

    /**
     * Rück-Sync: kopiert den Ordner eines Services in ein Template.
     *
     * @param serviceId Service-UUID
     * @param template  Ziel-Template
     * @return Future, das nach dem Kopieren abgeschlossen ist
     */
    CompletableFuture<Void> sync(String serviceId, String template);
}
