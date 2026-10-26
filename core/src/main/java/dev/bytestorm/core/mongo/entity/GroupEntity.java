package dev.bytestorm.core.mongo.entity;

import eu.koboo.en2do.repository.entity.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistenz-Entity einer Service-Gruppe in der Collection {@code groups} (§8, §9.1).
 *
 * <p>Enums liegen als Name im Dokument, damit die Daten lesbar und abfragbar
 * bleiben. Die Umwandlung nach {@link dev.bytestorm.proto.ServiceGroup} macht
 * der {@code ServiceGroupMapper}.
 */
@Getter
@Setter
@NoArgsConstructor
public class GroupEntity {

    @Id
    private String name;

    private String type;
    private String platform;
    private String version;
    private String mode;

    private List<String> templates = new ArrayList<>();
    private int minServices;
    private int maxServices;
    private int maxPlayers;
    private int memory;
    private List<String> jvmArgs = new ArrayList<>();
    private List<String> javaArgs = new ArrayList<>();

    private boolean fallback;
    private int fallbackPriority;
    private boolean maintenance;
    private Map<String, String> properties = new LinkedHashMap<>();
    private List<ScalingRuleData> scalingRules = new ArrayList<>();

    /** {@code null}, wenn die globale Runtime gilt. */
    private String runtime;

    /** {@code null}, wenn die globale Java-Version gilt. */
    private Integer javaVersion;
}
