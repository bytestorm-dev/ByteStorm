package dev.bytestorm.core.mongo.mapping;

import dev.bytestorm.core.mongo.entity.LoadRuleData;
import dev.bytestorm.core.mongo.entity.PlayerCountRuleData;
import dev.bytestorm.core.mongo.entity.ScheduleRuleData;
import dev.bytestorm.core.mongo.entity.ScalingRuleData;
import dev.bytestorm.proto.LoadRule;
import dev.bytestorm.proto.PlayerCountRule;
import dev.bytestorm.proto.ScheduleRule;
import dev.bytestorm.proto.ScalingRule;
import lombok.experimental.UtilityClass;

/**
 * Abbildung {@link ScalingRule} ↔ {@link ScalingRuleData}. {@code type} benennt
 * die aktive Regelart, damit das Dokument abfragbar bleibt.
 */
@UtilityClass
public final class ScalingRuleMapper {

    /** Typname der Spielerzahl-Regel. */
    public static final String TYPE_PLAYER_COUNT = "PLAYER_COUNT";

    /** Typname der Zeitplan-Regel. */
    public static final String TYPE_SCHEDULE = "SCHEDULE";

    /** Typname der Last-Regel. */
    public static final String TYPE_LOAD = "LOAD";

    /**
     * @param rule Proto-Regel
     * @return eingebettete Entity
     * @throws IllegalArgumentException wenn die Regel keine Regelart hat
     */
    public static ScalingRuleData toData(ScalingRule rule) {
        ScalingRuleData data = new ScalingRuleData();
        data.setId(rule.getId());
        data.setEnabled(rule.getEnabled());

        switch (rule.getRuleCase()) {
            case PLAYER_COUNT -> {
                PlayerCountRuleData playerCount = new PlayerCountRuleData();
                playerCount.setScaleUpUtilization(rule.getPlayerCount().getScaleUpUtilization());
                playerCount.setScaleUpServices(rule.getPlayerCount().getScaleUpServices());
                playerCount.setIdleUtilization(rule.getPlayerCount().getIdleUtilization());
                playerCount.setIdleMinutes(rule.getPlayerCount().getIdleMinutes());
                data.setType(TYPE_PLAYER_COUNT);
                data.setPlayerCount(playerCount);
            }
            case SCHEDULE -> {
                ScheduleRuleData schedule = new ScheduleRuleData();
                schedule.setCron(rule.getSchedule().getCron());
                schedule.setTimezone(rule.getSchedule().getTimezone());
                schedule.setMinServices(rule.getSchedule().getMinServices());
                schedule.setMaxServices(rule.getSchedule().getMaxServices());
                data.setType(TYPE_SCHEDULE);
                data.setSchedule(schedule);
            }
            case LOAD -> {
                LoadRuleData load = new LoadRuleData();
                load.setMaxHostCpu(rule.getLoad().getMaxHostCpu());
                load.setMaxHostMemory(rule.getLoad().getMaxHostMemory());
                load.setMinServices(rule.getLoad().getMinServices());
                data.setType(TYPE_LOAD);
                data.setLoad(load);
            }
            case RULE_NOT_SET -> throw new IllegalArgumentException("ScalingRule ohne Regel: " + rule.getId());
        }
        return data;
    }

    /**
     * @param data eingebettete Entity
     * @return Proto-Regel; ohne bekannten Typ bleibt die Regelart ungesetzt
     */
    public static ScalingRule toProto(ScalingRuleData data) {
        ScalingRule.Builder builder = ScalingRule.newBuilder()
                .setId(nullToEmpty(data.getId()))
                .setEnabled(data.isEnabled());

        String type = data.getType();
        if (TYPE_PLAYER_COUNT.equals(type) && data.getPlayerCount() != null) {
            builder.setPlayerCount(PlayerCountRule.newBuilder()
                    .setScaleUpUtilization(data.getPlayerCount().getScaleUpUtilization())
                    .setScaleUpServices(data.getPlayerCount().getScaleUpServices())
                    .setIdleUtilization(data.getPlayerCount().getIdleUtilization())
                    .setIdleMinutes(data.getPlayerCount().getIdleMinutes()));
        } else if (TYPE_SCHEDULE.equals(type) && data.getSchedule() != null) {
            builder.setSchedule(ScheduleRule.newBuilder()
                    .setCron(nullToEmpty(data.getSchedule().getCron()))
                    .setTimezone(nullToEmpty(data.getSchedule().getTimezone()))
                    .setMinServices(data.getSchedule().getMinServices())
                    .setMaxServices(data.getSchedule().getMaxServices()));
        } else if (TYPE_LOAD.equals(type) && data.getLoad() != null) {
            builder.setLoad(LoadRule.newBuilder()
                    .setMaxHostCpu(data.getLoad().getMaxHostCpu())
                    .setMaxHostMemory(data.getLoad().getMaxHostMemory())
                    .setMinServices(data.getLoad().getMinServices()));
        }
        return builder.build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
