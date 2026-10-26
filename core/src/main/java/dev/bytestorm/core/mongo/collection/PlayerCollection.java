package dev.bytestorm.core.mongo.collection;

import dev.bytestorm.core.mongo.entity.PlayerEntity;
import eu.koboo.en2do.repository.Collection;
import eu.koboo.en2do.repository.Repository;

import java.util.regex.Pattern;

/**
 * en2do-Repository für {@code players}. Die ID ist die Spieler-UUID.
 *
 * <p>Die Namenssuche läuft über den {@code Regex}-Operator mit einem
 * case-insensitiven {@link Pattern}; en2do leitet die Abfrage aus dem Methodennamen ab.
 */
@Collection("players")
public interface PlayerCollection extends Repository<PlayerEntity, String> {

    /**
     * @param name case-insensitives Muster auf den Namen
     * @return der erste passende Spieler oder {@code null}
     */
    PlayerEntity findFirstByNameRegex(Pattern name);
}
