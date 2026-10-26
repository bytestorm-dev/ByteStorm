package dev.bytestorm.core.mongo.entity;

import eu.koboo.en2do.repository.entity.Id;
import eu.koboo.en2do.repository.entity.compound.CompoundIndex;
import eu.koboo.en2do.repository.entity.compound.Index;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Spieler-Historie in der Collection {@code players} (§8). Zeiten liegen als
 * Epochen-Millis vor, damit das Dokument ohne Zusatz-Codecs lesbar bleibt.
 */
@Getter
@Setter
@NoArgsConstructor
// Ein-Feld-Index für die Namenssuche; @Id (uuid) ist über _id bereits eindeutig indiziert.
@CompoundIndex(value = @Index("name"))
public class PlayerEntity {

    @Id
    private String uuid;

    private String name;
    private long playtimeMillis;
    private long firstSeen;
    private long lastSeen;
    private String lastService;
}
