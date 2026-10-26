package dev.bytestorm.core.mongo.collection;

import dev.bytestorm.core.mongo.entity.ModuleConfigEntity;
import eu.koboo.en2do.repository.Collection;
import eu.koboo.en2do.repository.Repository;

/**
 * en2do-Repository für {@code modules_config}. Die ID ist der Modulname.
 */
@Collection("modules_config")
public interface ModuleConfigCollection extends Repository<ModuleConfigEntity, String> {
}
