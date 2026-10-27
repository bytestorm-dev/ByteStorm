package dev.bytestorm.launcher.scaling;

import dev.bytestorm.api.service.LaunchOptions;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.controller.scaling.ScalingActions;
import dev.bytestorm.core.config.CloudConfig;
import dev.bytestorm.launcher.layout.CloudLayout;
import dev.bytestorm.node.lifecycle.ServiceDefinition;
import dev.bytestorm.node.lifecycle.ServiceLifecycleException;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Erzeugt aus einer {@link ServiceGroup} eine startbare {@link ServiceDefinition}
 * und führt Start/Stop über den Node aus.
 *
 * <p>Der Provisioner ist die Brücke zwischen ScalingManager und Node: Der
 * Manager entscheidet, der Provisioner vergibt Name und Verzeichnis, wählt das
 * Plattform-Jar aus {@code platforms/} und startet über den injizierten
 * {@link Starter}. Eine Bridge-Jar wird nur installiert, wenn sie als
 * {@code lib/bridge-<platform>.jar} vorliegt.
 *
 * <p>Zusätzliche Properties eines einzelnen Starts ({@code services().start(group,
 * properties)}) werden über die Gruppe dieser einen Definition gelegt; sie landen
 * damit im {@code ServiceInfo.properties} des Services und überschreiben
 * gleichnamige Einträge der Gruppe (§9). Die Gruppe selbst bleibt unverändert.
 */
public final class ServiceProvisioner implements ScalingActions {

    /** Default-Arbeitsspeicher, wenn die Gruppe keinen Wert gesetzt hat. */
    private static final int DEFAULT_MEMORY_MB = 1024;

    private final Function<String, Optional<ServiceGroup>> groups;
    private final BiFunction<String, IntPredicate, Integer> names;
    private final Predicate<String> nameUsed;
    private final Starter starter;
    private final Stopper stopper;
    private final CloudLayout layout;
    private final CloudConfig config;
    private final PlatformProvider platformProvider;

    /**
     * @param groups   liefert eine Gruppe nach Namen
     * @param names    Namensvergabe, z. B. {@code NameAllocator::allocate}
     * @param nameUsed prüft, ob ein Service-Name bereits belegt ist
     * @param starter  startet die fertige Definition
     * @param stopper  stoppt einen Service
     * @param layout   Cloud-Layout mit {@code platforms/}, {@code running/} und {@code static/}
     * @param config   globale Runtime und Redis-Verbindung
     */
    public ServiceProvisioner(Function<String, Optional<ServiceGroup>> groups,
                              BiFunction<String, IntPredicate, Integer> names,
                              Predicate<String> nameUsed,
                              Starter starter,
                              Stopper stopper,
                              CloudLayout layout,
                              CloudConfig config) {
        this(groups, names, nameUsed, starter, stopper, layout, config, (platform, version) -> null);
    }

    /**
     * @param groups           liefert eine Gruppe nach Namen
     * @param names            Namensvergabe
     * @param nameUsed         prüft, ob ein Service-Name belegt ist
     * @param starter          startet die Definition
     * @param stopper          stoppt einen Service
     * @param layout           Cloud-Layout
     * @param config           globale Runtime und Redis-Verbindung
     * @param platformProvider lädt eine fehlende Plattform-Jar nach
     */
    public ServiceProvisioner(Function<String, Optional<ServiceGroup>> groups,
                              BiFunction<String, IntPredicate, Integer> names,
                              Predicate<String> nameUsed,
                              Starter starter,
                              Stopper stopper,
                              CloudLayout layout,
                              CloudConfig config,
                              PlatformProvider platformProvider) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.names = Objects.requireNonNull(names, "names");
        this.nameUsed = Objects.requireNonNull(nameUsed, "nameUsed");
        this.starter = Objects.requireNonNull(starter, "starter");
        this.stopper = Objects.requireNonNull(stopper, "stopper");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.config = Objects.requireNonNull(config, "config");
        this.platformProvider = Objects.requireNonNull(platformProvider, "platformProvider");
    }

    @Override
    public ServiceInfo start(String group, Map<String, String> properties) throws ServiceLifecycleException {
        ServiceGroup definition = withProperties(groups.apply(group)
                .orElseThrow(() -> new ServiceLifecycleException("Unbekannte Gruppe: " + group)), properties);
        String name = allocateName(definition);
        ServiceId id = ServiceId.random();
        Path platformJar = platformJar(definition);
        ServiceDefinition service = new ServiceDefinition(
                id,
                name,
                definition,
                serviceDirectory(definition, name, id),
                platformJar,
                bridgeJar(definition),
                launchOptions(definition),
                config.redis());
        return starter.start(service);
    }

    @Override
    public ServiceInfo stop(ServiceId id, boolean force) throws ServiceLifecycleException {
        return stopper.stop(id, force);
    }

    /**
     * Legt die Properties eines Starts über die der Gruppe. Die Gruppe kommt aus
     * dem Cache und darf nicht verändert werden, deshalb entsteht bei Bedarf eine
     * Kopie. Leere Namen werden abgewiesen, statt still im Service zu landen.
     *
     * @param group      Gruppen-Definition
     * @param properties zusätzliche Properties, darf {@code null} sein
     * @return die Definition für diesen Start
     */
    private static ServiceGroup withProperties(ServiceGroup group, Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return group;
        }
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("Property-Name darf nicht leer sein");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Property '" + entry.getKey() + "' hat keinen Wert");
            }
        }
        return group.toBuilder().putAllProperties(properties).build();
    }

    private String allocateName(ServiceGroup group) {
        int number = names.apply(group.getName(),
                candidate -> nameUsed.test(group.getName() + "-" + candidate));
        return group.getName() + "-" + number;
    }

    private Path serviceDirectory(ServiceGroup group, String name, ServiceId id) {
        if (group.getMode() == ServiceMode.STATIC) {
            return layout.staticServices().resolve(name);
        }
        return layout.running().resolve(name + "-" + id);
    }

    private Path platformJar(ServiceGroup group) throws ServiceLifecycleException {
        String platform = group.getPlatform() == null ? "" : group.getPlatform().trim();
        if (platform.isEmpty()) {
            throw new ServiceLifecycleException("Gruppe '" + group.getName() + "' hat keine Plattform");
        }
        if (platform.contains("/") || platform.contains("\\")) {
            throw new ServiceLifecycleException("Ungültige Plattform: " + platform);
        }

        String version = group.getVersion() == null ? "" : group.getVersion().trim();
        Path local = localPlatformJar(platform, version);
        if (local != null) {
            return local;
        }

        try {
            Path downloaded = platformProvider.ensure(platform, version.isEmpty() ? null : version);
            if (downloaded != null && Files.isRegularFile(downloaded)) {
                return downloaded;
            }
        } catch (RuntimeException e) {
            throw new ServiceLifecycleException("Plattform-Jar für '" + group.getName()
                    + "' konnte nicht geladen werden: " + e.getMessage(), e);
        }

        throw new ServiceLifecycleException("Plattform-Jar fehlt für '" + group.getName()
                + "' (" + platform + (version.isEmpty() ? "" : " " + version) + ")"
                + " — erwartet unter " + layout.platforms());
    }

    private Path localPlatformJar(String platform, String version) throws ServiceLifecycleException {
        if (!version.isEmpty()) {
            Path exact = layout.platforms().resolve(platform + "-" + version + ".jar");
            if (Files.isRegularFile(exact)) {
                return exact;
            }
        }
        Path plain = layout.platforms().resolve(platform + ".jar");
        if (Files.isRegularFile(plain)) {
            return plain;
        }

        List<Path> candidates = listPlatformJars(platform);
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.size() > 1) {
            throw new ServiceLifecycleException("Mehrere Plattform-Jars für '" + platform
                    + "' gefunden: " + candidates + " — Version in der Gruppe setzen");
        }
        return null;
    }

    private List<Path> listPlatformJars(String platform) throws ServiceLifecycleException {
        try (Stream<Path> files = Files.list(layout.platforms())) {
            List<Path> candidates = new ArrayList<>(files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(platform + "-") && name.endsWith(".jar");
                    })
                    .toList());
            candidates.sort(Comparator.comparing(path -> path.getFileName().toString()));
            return candidates;
        } catch (IOException e) {
            throw new ServiceLifecycleException("platforms/ nicht lesbar: " + layout.platforms(), e);
        }
    }

    private Path bridgeJar(ServiceGroup group) {
        String platform = group.getPlatform() == null ? "" : group.getPlatform().trim();
        if (platform.isEmpty()) {
            return null;
        }
        Path bridge = layout.lib().resolve("bridge-" + platform + ".jar");
        return Files.isRegularFile(bridge) ? bridge : null;
    }

    private LaunchOptions launchOptions(ServiceGroup group) {
        int memory = group.getMemory() > 0 ? group.getMemory() : DEFAULT_MEMORY_MB;
        return LaunchOptions.builder()
                .memoryMb(memory)
                .jvmArgs(group.getJvmArgsList())
                .javaArgs(group.getJavaArgsList())
                .runtime(group.hasRuntime() ? group.getRuntime() : config.runtime())
                .javaVersion(group.hasJavaVersion() ? group.getJavaVersion() : null)
                .build();
    }

    /**
     * Startet eine fertige Definition, z. B. über {@code ServiceManager::start}.
     */
    @FunctionalInterface
    public interface Starter {

        /**
         * @param definition Service-Definition
         * @return der gestartete Service
         * @throws ServiceLifecycleException wenn der Start fehlschlägt
         */
        ServiceInfo start(ServiceDefinition definition) throws ServiceLifecycleException;
    }

    /**
     * Lädt eine fehlende Plattform-Jar nach, z. B. über den
     * {@code PlatformDownloader}.
     */
    @FunctionalInterface
    public interface PlatformProvider {

        /**
         * @param platform Projektname, z. B. {@code paper}
         * @param version  gewünschte Version oder {@code null}
         * @return die bereitgestellte Jar oder {@code null}, wenn nicht verfügbar
         * @throws ServiceLifecycleException wenn der Download fehlschlägt
         */
        Path ensure(String platform, String version) throws ServiceLifecycleException;
    }

    /**
     * Stoppt einen Service, z. B. über den Controller.
     */
    @FunctionalInterface
    public interface Stopper {

        /**
         * @param id    Service-ID
         * @param force {@code true} killt sofort
         * @return der gestoppte Service
         * @throws ServiceLifecycleException wenn der Stop fehlschlägt
         */
        ServiceInfo stop(ServiceId id, boolean force) throws ServiceLifecycleException;
    }
}
