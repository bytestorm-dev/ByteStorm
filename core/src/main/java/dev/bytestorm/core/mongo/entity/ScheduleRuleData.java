package dev.bytestorm.core.mongo.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Eingebettete Zeitplan-Regel (§11).
 */
@Getter
@Setter
@NoArgsConstructor
public class ScheduleRuleData {

    private String cron;
    private String timezone;
    private int minServices;
    private int maxServices;
}
