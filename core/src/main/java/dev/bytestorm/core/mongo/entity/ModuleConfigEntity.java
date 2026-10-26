package dev.bytestorm.core.mongo.entity;

import eu.koboo.en2do.repository.entity.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Konfiguration eines optionalen Moduls in der Collection {@code modules_config} (§8, §16).
 */
@Getter
@Setter
@NoArgsConstructor
public class ModuleConfigEntity {

    @Id
    private String name;

    private boolean enabled;
    private Map<String, String> settings = new LinkedHashMap<>();
}
