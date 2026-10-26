package dev.bytestorm.core.mongo.entity;

import eu.koboo.en2do.repository.entity.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Alle Scaling-Regeln einer Gruppe in der Collection {@code scaling_rules} (§8, §11).
 *
 * <p>Bewusst ein Dokument pro Gruppe: Regeln werden immer gemeinsam ersetzt,
 * damit ein Gruppen-Update atomar bleibt.
 */
@Getter
@Setter
@NoArgsConstructor
public class ScalingRuleSetEntity {

    @Id
    private String group;

    private List<ScalingRuleData> rules = new ArrayList<>();
}
