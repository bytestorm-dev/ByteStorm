package dev.bytestorm.core.mongo.collection;

import dev.bytestorm.core.mongo.entity.ScalingRuleSetEntity;
import eu.koboo.en2do.repository.Collection;
import eu.koboo.en2do.repository.Repository;

/**
 * en2do-Repository für {@code scaling_rules}. Die ID ist der Gruppenname.
 */
@Collection("scaling_rules")
public interface ScalingRuleCollection extends Repository<ScalingRuleSetEntity, String> {
}
