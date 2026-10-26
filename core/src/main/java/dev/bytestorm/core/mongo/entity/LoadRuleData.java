package dev.bytestorm.core.mongo.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Eingebettete Host-Last-Regel (§11).
 */
@Getter
@Setter
@NoArgsConstructor
public class LoadRuleData {

    private double maxHostCpu;
    private double maxHostMemory;
    private int minServices;
}
