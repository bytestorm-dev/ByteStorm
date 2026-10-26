package dev.bytestorm.core.mongo.mapping;

import dev.bytestorm.core.mongo.entity.ModuleConfigEntity;
import dev.bytestorm.core.mongo.model.ModuleConfig;
import lombok.experimental.UtilityClass;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Abbildung {@link ModuleConfig} ↔ {@link ModuleConfigEntity} (§8, §16).
 */
@UtilityClass
public final class ModuleConfigMapper {

    /**
     * @param config Domain-Modell
     * @return en2do-Entity
     */
    public static ModuleConfigEntity toEntity(ModuleConfig config) {
        ModuleConfigEntity entity = new ModuleConfigEntity();
        entity.setName(config.name());
        entity.setEnabled(config.enabled());
        entity.setSettings(new LinkedHashMap<>(config.settings()));
        return entity;
    }

    /**
     * @param entity en2do-Entity
     * @return Domain-Modell
     */
    public static ModuleConfig toDomain(ModuleConfigEntity entity) {
        Map<String, String> settings = entity.getSettings() == null ? Map.of() : entity.getSettings();
        return new ModuleConfig(entity.getName(), entity.isEnabled(), settings);
    }
}
