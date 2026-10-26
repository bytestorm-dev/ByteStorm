package dev.bytestorm.core.mongo.mapping;

import dev.bytestorm.core.mongo.entity.TemplateEntity;
import dev.bytestorm.proto.TemplateInfo;
import lombok.experimental.UtilityClass;

/**
 * Abbildung {@link TemplateInfo} ↔ {@link TemplateEntity} (§8, §10).
 */
@UtilityClass
public final class TemplateMapper {

    /**
     * @param template Proto-Metadaten
     * @return en2do-Entity
     */
    public static TemplateEntity toEntity(TemplateInfo template) {
        TemplateEntity entity = new TemplateEntity();
        entity.setName(template.getName());
        entity.setDescription(template.getDescription());
        entity.setHash(template.getHash());
        entity.setLastModified(template.getLastModified());
        return entity;
    }

    /**
     * @param entity en2do-Entity
     * @return Proto-Metadaten
     */
    public static TemplateInfo toProto(TemplateEntity entity) {
        return TemplateInfo.newBuilder()
                .setName(nullToEmpty(entity.getName()))
                .setDescription(nullToEmpty(entity.getDescription()))
                .setHash(nullToEmpty(entity.getHash()))
                .setLastModified(entity.getLastModified())
                .build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
