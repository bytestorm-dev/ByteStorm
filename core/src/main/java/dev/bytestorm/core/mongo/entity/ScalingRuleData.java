package dev.bytestorm.core.mongo.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Eingebettete Scaling-Regel. {@code type} benennt die aktive Regelart, die
 * übrigen Felder sind je nach Art {@code null}.
 */
@Getter
@Setter
@NoArgsConstructor
public class ScalingRuleData {

    private String id;
    private boolean enabled;

    /** {@code PLAYER_COUNT}, {@code SCHEDULE} oder {@code LOAD}. */
    private String type;

    private PlayerCountRuleData playerCount;
    private ScheduleRuleData schedule;
    private LoadRuleData load;
}
