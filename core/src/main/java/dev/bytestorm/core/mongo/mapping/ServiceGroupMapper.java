package dev.bytestorm.core.mongo.mapping;

import dev.bytestorm.core.mongo.entity.GroupEntity;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceType;
import dev.bytestorm.proto.ScalingRule;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Abbildung {@link ServiceGroup} ↔ {@link GroupEntity}.
 */
@UtilityClass
public final class ServiceGroupMapper {

    /**
     * @param group Proto-Definition
     * @return en2do-Entity
     */
    public static GroupEntity toEntity(ServiceGroup group) {
        GroupEntity entity = new GroupEntity();
        entity.setName(group.getName());
        entity.setType(group.getType().name());
        entity.setPlatform(group.getPlatform());
        entity.setVersion(group.getVersion());
        entity.setMode(group.getMode().name());
        entity.setTemplates(new ArrayList<>(group.getTemplatesList()));
        entity.setMinServices(group.getMinServices());
        entity.setMaxServices(group.getMaxServices());
        entity.setMaxPlayers(group.getMaxPlayers());
        entity.setMemory(group.getMemory());
        entity.setJvmArgs(new ArrayList<>(group.getJvmArgsList()));
        entity.setJavaArgs(new ArrayList<>(group.getJavaArgsList()));
        entity.setFallback(group.getFallback());
        entity.setFallbackPriority(group.getFallbackPriority());
        entity.setMaintenance(group.getMaintenance());
        entity.setProperties(new LinkedHashMap<>(group.getPropertiesMap()));

        List<dev.bytestorm.core.mongo.entity.ScalingRuleData> rules = new ArrayList<>();
        for (ScalingRule rule : group.getScalingRulesList()) {
            rules.add(ScalingRuleMapper.toData(rule));
        }
        entity.setScalingRules(rules);

        if (group.hasRuntime()) {
            entity.setRuntime(group.getRuntime().name());
        }
        if (group.hasJavaVersion()) {
            entity.setJavaVersion(group.getJavaVersion());
        }
        return entity;
    }

    /**
     * @param entity en2do-Entity
     * @return Proto-Definition
     */
    public static ServiceGroup toProto(GroupEntity entity) {
        ServiceGroup.Builder builder = ServiceGroup.newBuilder()
                .setName(nullToEmpty(entity.getName()))
                .setType(parse(ServiceType.class, entity.getType(), ServiceType.PROXY))
                .setPlatform(nullToEmpty(entity.getPlatform()))
                .setVersion(nullToEmpty(entity.getVersion()))
                .setMode(parse(ServiceMode.class, entity.getMode(), ServiceMode.DYNAMIC))
                .addAllTemplates(nullSafe(entity.getTemplates()))
                .setMinServices(entity.getMinServices())
                .setMaxServices(entity.getMaxServices())
                .setMaxPlayers(entity.getMaxPlayers())
                .setMemory(entity.getMemory())
                .addAllJvmArgs(nullSafe(entity.getJvmArgs()))
                .addAllJavaArgs(nullSafe(entity.getJavaArgs()))
                .setFallback(entity.isFallback())
                .setFallbackPriority(entity.getFallbackPriority())
                .setMaintenance(entity.isMaintenance())
                .putAllProperties(nullSafeProperties(entity.getProperties()));

        if (entity.getScalingRules() != null) {
            for (dev.bytestorm.core.mongo.entity.ScalingRuleData rule : entity.getScalingRules()) {
                builder.addScalingRules(ScalingRuleMapper.toProto(rule));
            }
        }
        if (entity.getRuntime() != null && !entity.getRuntime().isBlank()) {
            builder.setRuntime(parse(RuntimeType.class, entity.getRuntime(), RuntimeType.PROCESS));
        }
        if (entity.getJavaVersion() != null) {
            builder.setJavaVersion(entity.getJavaVersion());
        }
        return builder.build();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static Map<String, String> nullSafeProperties(Map<String, String> properties) {
        return properties == null ? Map.of() : properties;
    }
}
