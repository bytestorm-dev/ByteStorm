package dev.bytestorm.launcher.wizard;

import dev.bytestorm.core.config.MongoSettings;
import dev.bytestorm.core.mongo.MongoProvider;
import dev.bytestorm.core.mongo.repository.GroupRepository;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceType;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Objects;

/**
 * Standardgruppen für den Erststart (§5.1, §9.1): ein statischer Proxy und eine
 * dynamische Lobby mit Fallback.
 *
 * <p>Bestehende Gruppen werden nicht überschrieben — ein erneuter Wizard-Lauf
 * lässt Anpassungen des Betreibers unangetastet.
 */
@UtilityClass
public final class DefaultGroups {

    /** Name der Proxy-Gruppe. */
    public static final String PROXY = "Proxy";

    /** Name der Lobby-Gruppe. */
    public static final String LOBBY = "Lobby";

    /**
     * @return die Standardgruppen
     */
    public static List<ServiceGroup> definitions() {
        return List.of(proxy(), lobby());
    }

    /**
     * Legt die Standardgruppen an, sofern sie noch nicht existieren.
     *
     * @param settings MongoDB-Verbindung
     */
    public static void create(MongoSettings settings) {
        Objects.requireNonNull(settings, "settings");
        try (MongoProvider mongo = MongoProvider.connect(settings)) {
            GroupRepository repository = new GroupRepository(mongo);
            for (ServiceGroup group : definitions()) {
                if (repository.find(group.getName()).isEmpty()) {
                    repository.upsert(group);
                }
            }
        }
    }

    private static ServiceGroup proxy() {
        return ServiceGroup.newBuilder()
                .setName(PROXY)
                .setType(ServiceType.PROXY)
                .setPlatform("velocity")
                .setMode(ServiceMode.STATIC)
                .setMinServices(1)
                .setMaxServices(1)
                .setMemory(512)
                .build();
    }

    private static ServiceGroup lobby() {
        return ServiceGroup.newBuilder()
                .setName(LOBBY)
                .setType(ServiceType.SERVER)
                .setPlatform("paper")
                .setMode(ServiceMode.DYNAMIC)
                // ALL/SERVER/FALLBACK decken die gemeinsamen Dateien ab; eigene Gruppen-Templates
                // kann der Betreiber ergänzen.
                .setMinServices(1)
                .setMaxServices(3)
                .setMaxPlayers(100)
                .setMemory(2048)
                .setFallback(true)
                .setFallbackPriority(1)
                .build();
    }
}
