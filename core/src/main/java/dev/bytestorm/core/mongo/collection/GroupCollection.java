package dev.bytestorm.core.mongo.collection;

import dev.bytestorm.core.mongo.entity.GroupEntity;
import eu.koboo.en2do.repository.Collection;
import eu.koboo.en2do.repository.Repository;

/**
 * en2do-Repository für {@code groups}. Die ID ist der Gruppenname.
 */
@Collection("groups")
public interface GroupCollection extends Repository<GroupEntity, String> {
}
