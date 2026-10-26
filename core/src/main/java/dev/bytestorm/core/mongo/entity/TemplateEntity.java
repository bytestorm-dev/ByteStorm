package dev.bytestorm.core.mongo.entity;

import eu.koboo.en2do.repository.entity.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Metadaten eines Templates in der Collection {@code templates} (§8, §10).
 */
@Getter
@Setter
@NoArgsConstructor
public class TemplateEntity {

    @Id
    private String name;

    private String description;
    private String hash;

    /** Zeitpunkt der letzten Änderung als Epochen-Millis. */
    private long lastModified;
}
