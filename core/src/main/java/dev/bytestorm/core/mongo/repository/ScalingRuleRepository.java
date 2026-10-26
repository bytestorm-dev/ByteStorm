package dev.bytestorm.core.mongo.repository;

import dev.bytestorm.core.mongo.MongoProvider;
import dev.bytestorm.core.mongo.collection.ScalingRuleCollection;
import dev.bytestorm.core.mongo.entity.ScalingRuleData;
import dev.bytestorm.core.mongo.entity.ScalingRuleSetEntity;
import dev.bytestorm.core.mongo.mapping.ScalingRuleMapper;
import dev.bytestorm.proto.ScalingRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Scaling-Regeln über das en2do-Repository {@link ScalingRuleCollection} (§8, §11).
 * Alle Regeln einer Gruppe liegen in einem Dokument und werden gemeinsam ersetzt.
 */
public final class ScalingRuleRepository {

    private final ScalingRuleCollection collection;

    /**
     * @param provider verbundener Mongo-Provider
     */
    public ScalingRuleRepository(MongoProvider provider) {
        this.collection = provider.repository(ScalingRuleCollection.class);
    }

    /**
     * @param group Gruppenname
     * @return Regeln der Gruppe, leer wenn keine hinterlegt sind
     */
    public List<ScalingRule> findByGroup(String group) {
        ScalingRuleSetEntity entity = collection.findFirstById(group);
        if (entity == null || entity.getRules() == null) {
            return List.of();
        }
        return entity.getRules().stream().map(ScalingRuleMapper::toProto).toList();
    }

    /**
     * Ersetzt alle Regeln einer Gruppe.
     *
     * @param group Gruppenname
     * @param rules neue Regeln; eine leere Liste löscht den Eintrag
     */
    public void replace(String group, List<ScalingRule> rules) {
        if (rules.isEmpty()) {
            collection.deleteById(group);
            return;
        }
        ScalingRuleSetEntity entity = new ScalingRuleSetEntity();
        entity.setGroup(group);
        List<ScalingRuleData> data = new ArrayList<>(rules.size());
        for (ScalingRule rule : rules) {
            data.add(ScalingRuleMapper.toData(rule));
        }
        entity.setRules(data);
        collection.save(entity);
    }

    /**
     * @param group Gruppenname
     */
    public void deleteByGroup(String group) {
        collection.deleteById(group);
    }
}
