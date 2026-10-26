package dev.bytestorm.core.mongo.collection;

import dev.bytestorm.core.mongo.entity.TemplateEntity;
import eu.koboo.en2do.repository.Collection;
import eu.koboo.en2do.repository.Repository;

/**
 * en2do-Repository für {@code templates}. Die ID ist der Templatename.
 */
@Collection("templates")
public interface TemplateCollection extends Repository<TemplateEntity, String> {
}
