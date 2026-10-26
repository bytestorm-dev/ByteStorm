package dev.bytestorm.core.mongo.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Eingebettete Spielerzahl-Regel (§11).
 */
@Getter
@Setter
@NoArgsConstructor
public class PlayerCountRuleData {

    private double scaleUpUtilization;
    private int scaleUpServices;
    private double idleUtilization;
    private int idleMinutes;
}
