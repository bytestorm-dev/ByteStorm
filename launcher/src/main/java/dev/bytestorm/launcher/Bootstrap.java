package dev.bytestorm.launcher;

import dev.bytestorm.api.ByteStorm;
import dev.bytestorm.api.health.CloudHealth;
import dev.bytestorm.api.service.PortRange;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.cli.AlertsCommandLine;
import dev.bytestorm.cli.BackupCommandLine;
import dev.bytestorm.cli.EulaCommandLine;
import dev.bytestorm.cli.GroupCommandLine;
import dev.bytestorm.cli.ModuleCommandLine;
import dev.bytestorm.cli.PlatformCommandLine;
import dev.bytestorm.cli.PlayerCommandLine;
import dev.bytestorm.cli.ReloadCommandLine;
import dev.bytestorm.cli.ServiceCommandLine;
import dev.bytestorm.cli.ServiceConsole;
import dev.bytestorm.cli.TemplateCommandLine;
import dev.bytestorm.cli.UpdaterCommandLine;
import dev.bytestorm.controller.DefaultServiceController;
import dev.bytestorm.controller.ServiceController;
import dev.bytestorm.controller.group.GroupManager;
import dev.bytestorm.controller.player.PlayerManager;
import dev.bytestorm.controller.registry.ServiceRegistry;
import dev.bytestorm.controller.scaling.ScalingManager;
import dev.bytestorm.controller.scaling.SystemHostMetrics;
import dev.bytestorm.controller.template.TemplateManager;
import dev.bytestorm.codec.ProtobufCodec;
import dev.bytestorm.codec.TypeRegistry;
import dev.bytestorm.core.alert.AlertFileSink;
import dev.bytestorm.core.alert.AlertService;
import dev.bytestorm.core.alert.LogAlertSink;
import dev.bytestorm.core.config.CloudConfig;
import dev.bytestorm.core.config.ConfigException;
import dev.bytestorm.core.config.ConfigService;
import dev.bytestorm.core.config.PortSettings;
import dev.bytestorm.core.health.ComponentHealth;
import dev.bytestorm.core.health.HealthProbe;
import dev.bytestorm.core.health.HealthReport;
import dev.bytestorm.core.health.Healthcheck;
import dev.bytestorm.core.lifecycle.ShutdownCoordinator;
import dev.bytestorm.core.logging.Logging;
import dev.bytestorm.core.module.ModuleManager;
import dev.bytestorm.core.module.ModuleStore;
import dev.bytestorm.core.module.MongoModuleStore;
import dev.bytestorm.core.mongo.MongoProvider;
import dev.bytestorm.core.mongo.MongoUnavailableException;
import dev.bytestorm.core.mongo.repository.GroupRepository;
import dev.bytestorm.core.mongo.repository.ModuleConfigRepository;
import dev.bytestorm.core.update.Updater;
import dev.bytestorm.core.redis.RedisProvider;
import dev.bytestorm.core.redis.RedisUnavailableException;
import dev.bytestorm.core.redis.ServiceReconciliation;
import dev.bytestorm.launcher.alert.AlertMonitor;
import dev.bytestorm.launcher.api.DefaultByteStormApi;
import dev.bytestorm.launcher.backup.CloudBackup;
import dev.bytestorm.launcher.doctor.ControllerRepair;
import dev.bytestorm.launcher.doctor.Doctor;
import dev.bytestorm.launcher.doctor.RestExposure;
import dev.bytestorm.launcher.layout.CloudLayout;
import dev.bytestorm.launcher.layout.DefaultTemplates;
import dev.bytestorm.launcher.layout.ServiceDirectories;
import dev.bytestorm.launcher.luckperms.LuckPermsInstaller;
import dev.bytestorm.launcher.platform.PlatformDownloader;
import dev.bytestorm.launcher.recovery.ServiceRecovery;
import dev.bytestorm.launcher.scaling.ServiceProvisioner;
import dev.bytestorm.launcher.update.UpdateNotifier;
import dev.bytestorm.launcher.wizard.DefaultConnectionTester;
import dev.bytestorm.launcher.wizard.GroupSetup;
import dev.bytestorm.launcher.wizard.DefaultModules;
import dev.bytestorm.launcher.wizard.FirstRunWizard;
import dev.bytestorm.launcher.wizard.JLineWizardIo;
import dev.bytestorm.launcher.wizard.WizardAbortedException;
import dev.bytestorm.core.mongo.repository.PlayerRepository;
import dev.bytestorm.core.player.PlayerRegistry;
import dev.bytestorm.node.bridge.BridgeRegistrar;
import dev.bytestorm.node.crash.CrashHandler;
import dev.bytestorm.node.crash.CrashReportWriter;
import dev.bytestorm.node.crash.CrashSupport;
import dev.bytestorm.node.lifecycle.ServiceManager;
import dev.bytestorm.node.template.TemplatePreparer;
import dev.bytestorm.node.runtime.DockerRuntime;
import dev.bytestorm.node.runtime.PortLease;
import dev.bytestorm.node.runtime.ProcessRuntime;
import dev.bytestorm.node.runtime.RuntimeFactory;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceType;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Startet die Cloud im Single-Node-Betrieb (§5).
 *
 * <p>Reihenfolge: Logging → {@code config/cloud.json} → MongoDB → Redis →
 * Healthcheck. Jede Ressource wird sofort beim {@link ShutdownCoordinator}
 * registriert; der Coordinator kehrt die Reihenfolge beim Beenden um, schließt
 * also Redis vor MongoDB. Ein Fehler beim Start fährt bereits geöffnete
 * Ressourcen wieder herunter, statt sie dem Prozess zu überlassen.
 */
@Slf4j
public final class Bootstrap implements AutoCloseable {

    /** Pfad der Konfiguration, relativ zum Cloud-Verzeichnis. */
    public static final String CONFIG_FILE = "config/cloud.json";

    private static final Duration HEALTHCHECK_TIMEOUT = Duration.ofSeconds(5);

    /** Abstand der Heartbeat-Prüfung; die Frist selbst liegt im {@link ServiceManager}. */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);

    private final Path cloudDirectory;
    private final CloudLayout layout;
    private final Path logFile;
    private final ConfigService configService;
    private final MongoProvider mongo;
    private final RedisProvider redis;
    private final CoreServices core;
    private final ServiceManager services;
    private final BridgeRegistrar bridgeRegistrar;
    private final ServiceController controller;
    private final ScalingManager scaling;
    private final ServiceCommandLine commandLine;
    private final Healthcheck healthcheck;
    private final ShutdownCoordinator shutdown;

    private Bootstrap(Path cloudDirectory,
                      CloudLayout layout,
                      Path logFile,
                      ConfigService configService,
                      MongoProvider mongo,
                      RedisProvider redis,
                      CoreServices core,
                      ServiceManager services,
                      BridgeRegistrar bridgeRegistrar,
                      ServiceController controller,
                      ScalingManager scaling,
                      ServiceCommandLine commandLine,
                      Healthcheck healthcheck,
                      ShutdownCoordinator shutdown) {
        this.cloudDirectory = cloudDirectory;
        this.layout = layout;
        this.logFile = logFile;
        this.configService = configService;
        this.mongo = mongo;
        this.redis = redis;
        this.core = core;
        this.services = services;
        this.bridgeRegistrar = bridgeRegistrar;
        this.controller = controller;
        this.scaling = scaling;
        this.commandLine = commandLine;
        this.healthcheck = healthcheck;
        this.shutdown = shutdown;
    }

    /**
     * Startet die Cloud und prüft alle Bestandteile.
     *
     * @param cloudDirectory Verzeichnis mit {@code config/cloud.json} und {@code logs/}
     * @return gestarteter Bootstrap
     * @throws ConfigException          wenn die Konfiguration fehlt oder ungültig ist
     * @throws MongoUnavailableException wenn MongoDB nicht erreichbar ist
     * @throws RedisUnavailableException wenn Redis nicht erreichbar ist
     * @throws IllegalStateException    wenn der Healthcheck fehlschlägt
     */
    public static Bootstrap start(Path cloudDirectory) throws ConfigException {
        Objects.requireNonNull(cloudDirectory, "cloudDirectory");

        Path directory = cloudDirectory.toAbsolutePath().normalize();
        Path logFile = Logging.configure(directory);
        log.info("ByteStorm startet in {} (Log: {})", directory, logFile);

        CloudLayout layout = new CloudLayout(directory);
        try {
            layout.create();
        } catch (IOException e) {
            throw new UncheckedIOException("Verzeichnislayout konnte nicht angelegt werden: " + directory, e);
        }
        log.info("Verzeichnislayout geprüft ({}/)", layout.root().getFileName());
        try {
            List<Path> createdTemplates = DefaultTemplates.write(layout);
            if (!createdTemplates.isEmpty()) {
                log.info("Standard-Templates angelegt: {}", createdTemplates);
            }
        } catch (IOException e) {
            // Ohne Templates starten Services ohne Port/Forwarding — kein Grund, die Cloud zu verweigern.
            log.warn("Standard-Templates nicht angelegt: {}", e.getMessage());
        }

        ConfigService configService = ConfigService.load(directory.resolve(CONFIG_FILE));
        CloudConfig config = configService.current();
        if (!config.proxy().hasSecret()) {
            // Einmaliges Erzeugen und Speichern; das Secret geht als %SECRET% an die Templates (§14).
            config = configService.ensureForwardingSecret();
            log.info("Forwarding-Secret erzeugt und in {} gespeichert", CONFIG_FILE);
        } else {
            log.info("Forwarding-Secret aus {} übernommen", CONFIG_FILE);
        }

        // Die Validierung lässt das nur mit dieser Zusage zu (§20): einmal pro Start
        // sichtbar machen, welche Datenbank hier offen im Netz steht.
        if (config.security().mongoAllowsInsecure()) {
            log.warn("MongoDB ohne Anmeldung (security.allowInsecureMongo): wer im selben Netz "
                    + "sitzt, kann den gesamten Zustand lesen und verändern — in {} abschalten, "
                    + "sobald ein Benutzer gesetzt ist", CONFIG_FILE);
        }
        if (config.security().redisAllowsInsecure()) {
            log.warn("Redis ohne Passwort (security.allowInsecureRedis): wer im selben Netz "
                    + "sitzt, kann den Live-Zustand lesen und verändern — in {} abschalten, "
                    + "sobald ein Passwort gesetzt ist", CONFIG_FILE);
        }

        ShutdownCoordinator shutdown = new ShutdownCoordinator();
        try {
            // Registrierung in Startreihenfolge — der Coordinator dreht sie um.
            MongoProvider mongo = MongoProvider.connect(config.mongo());
            shutdown.registerCloseable("mongo", mongo);
            log.info("MongoDB verbunden: {}", config.mongo());

            TypeRegistry registry = TypeRegistry.byteStormDefaults();
            ProtobufCodec codec = new ProtobufCodec(registry);
            RedisProvider redis = RedisProvider.connect(config.redis(), codec);
            shutdown.registerCloseable("redis", redis);
            log.info("Redis verbunden: {}", config.redis());

            CoreServices core = CoreServices.create(redis.client(), registry, codec);
            // Nach Redis registriert: der Coordinator schließt den Bus vor der Verbindung.
            shutdown.registerCloseable("events", core.events());
            log.info("Event-Bus und RPC bereit ({} Proto-Typen)", registry.size());

            PlayerRegistry players = new PlayerRegistry(core.events(), core.players(),
                    new PlayerRepository(mongo));
            shutdown.registerCloseable("players", players);
            log.info("Spieler-Registry bereit");

            ProcessRuntime processRuntime = new ProcessRuntime(layout.logs(), core.logs());
            ServiceManager services = nodeServices(config, layout, core, processRuntime);
            shutdown.registerCloseable("services", services);
            // Gruppe-Quelle für CLI, Provisioner und Scaling; ein Pfad mit Validierung und Cache.
            // Vor dem Abgleich angelegt: Ein übernommener Prozess braucht seine Gruppe.
            GroupManager groups = new GroupManager(new GroupRepository(mongo), core.groups(),
                    config.ports(), name -> Files.isDirectory(layout.templates().resolve(name)));
            // Prozesse aus einem früheren Lauf: übernehmen oder aufräumen, bevor die Registry
            // geleert wird — sonst hätten sie keinen Namen, keinen Port und keinen Heartbeat.
            ServiceRecovery.Report recovered = new ServiceRecovery(layout, groups::get, config.redis(),
                    core.ports()::release, services).run();
            if (!recovered.isEmpty()) {
                log.info("Laufende Prozesse aus einem früheren Start: {}", recovered.describe());
            }
            reconcile(layout, core, recovered.adoptedIds(), recovered.adoptedPorts());
            // Nach dem Manager registriert: Handshake/Heartbeat enden vor dem Stop der Services.
            BridgeRegistrar bridgeRegistrar = new BridgeRegistrar(redis.client(), services, core.services());
            bridgeRegistrar.start();
            shutdown.registerCloseable("bridge", bridgeRegistrar);
            // Nach dem Registrar registriert, damit der Sweep zuerst endet.
            shutdown.registerCloseable("heartbeat", startHeartbeatSweep(services));
            // Lesen über Redis (Quelle der Wahrheit), Schreiben über den Node (§7.1).
            ServiceRegistry serviceRegistry = ServiceRegistry.of(core.services(), services::all);
            ServiceController controller = new DefaultServiceController(services, serviceRegistry);
            ServiceProvisioner provisioner = provisioner(groups, services, core, layout, config);
            // Degradierte Gruppen bekommen keine neuen Services: die Skalierung kann den
            // Absturzgrund nicht beheben, sie würde nur endlos Nachschub starten.
            ScalingManager scaling = new ScalingManager(groups::all, services::all,
                    provisioner, config.scaling(), services::isDegraded);
            shutdown.registerCloseable("scaling", scaling);
            scaling.start();
            // Templates liegen als Ordner unter templates/; der Rück-Sync findet den
            // Service-Ordner über den Namen (§10, §17).
            TemplateManager templates = new TemplateManager(layout.templates(),
                    name -> ServiceDirectories.directory(layout, name));
            // Spieler-Aktionen gehen an den Proxy des Spielers; die Registry liefert ihn (§14).
            PlayerRepository playerRepository = new PlayerRepository(mongo);
            PlayerManager playerManager = new PlayerManager(core.players()::all,
                    playerRepository::findByName, serviceRegistry, core.bridgeCommands());
            // `group scale` geht über dieselbe Scaling-Logik: Schutzregeln und Zeitstempel
            // dürfen nicht umgangen werden (§11, §17).
            // Module liegen als Jar in lib/ und melden sich über den ServiceLoader (§16);
            // jeder Zustandswechsel geht als ModuleStateChanged über den Event-Bus.
            ModuleStore moduleStore = new MongoModuleStore(new ModuleConfigRepository(mongo));
            ModuleManager modules = new ModuleManager(moduleStore, layout.modules(), ByteStorm::api,
                    core.events()::publish);
            shutdown.registerCloseable("modules", modules);
            // Die öffentliche API (§15) steht, bevor Module geladen werden: Sie ist deren
            // Zugang zur Cloud und später die Grundlage des REST-Moduls.
            // Vor der API angelegt: `/health`, `/nodes/status` und `/metrics` lesen
            // denselben Bericht, den der Start selbst prüft (§11).
            Healthcheck healthcheck = Healthcheck.of(mongo, redis).withRuntime(runtimeProbe(config));
            // Alarme (§20): derselbe Healthcheck wie /health, dazu DEGRADED-Gruppen,
            // abgestürzte Services und knapper Speicher. Jeder Zustand wird einmal
            // gemeldet und einmal als behoben — Log und logs/alerts.log.
            AlertService alertService = new AlertService(List.of(new LogAlertSink(),
                    new AlertFileSink(layout.logs().resolve(AlertFileSink.FILE_NAME))));
            AlertMonitor alertMonitor = new AlertMonitor(healthcheck, groups::all, services::all,
                    services::isDegraded, directory, alertService);
            alertMonitor.start();
            shutdown.registerCloseable("alerts", alertMonitor);
            DefaultByteStormApi api = new DefaultByteStormApi(controller, provisioner, groups, scaling,
                    DefaultByteStormApi.PlayerAccess.of(playerManager, playerRepository), templates,
                    modules, core.events()::subscribe, () -> toCloudHealth(healthcheck));
            ByteStorm.install(api);
            shutdown.register("api", () -> {
                ByteStorm.uninstall();
                api.close();
            });
            log.info("Öffentliche API installiert");
            modules.start();
            // Plattform-Jars kommen weiterhin aus platforms/, die CLI liest dasselbe Verzeichnis (§13).
            PlatformDownloader platformDownloader = new PlatformDownloader(layout.platforms());
            Updater updater = new Updater(directory);
            // Einmalige Prüfung im Hintergrund: nur ein Hinweis im Log, kein automatisches Update (§17).
            UpdateNotifier.checkInBackground(updater);
            ServiceCommandLine commandLine = new ServiceCommandLine(controller,
                    new GroupCommandLine(groups, scaling),
                    new TemplateCommandLine(templates),
                    new PlayerCommandLine(playerManager),
                    new ReloadCommandLine(configService, groups),
                    new ModuleCommandLine(modules),
                    new PlatformCommandLine(layout.platforms(),
                            platformDownloader::availableVersions, platformDownloader::ensure),
                    new UpdaterCommandLine(updater),
                    // Dieselbe Messquelle wie das Scaling: CPU und RAM im Dashboard (§17).
                    new SystemHostMetrics(),
                    // `doctor` prüft Pfade, Ports, Java, Datenbanken, Adressen, Forwarding
                    // und die Erreichbarkeit der REST-API (§11, §14, §20).
                    new Doctor(layout, configService::current, groups::all, controller::services,
                            () -> timed(mongo::ping), () -> timed(redis::ping),
                            // `doctor --fix` wendet die Templates an und startet laufende
                            // Services dafür neu.
                            new ControllerRepair(controller),
                            // Nur Adresse und Key-Namen, nie die Werte der Keys.
                            () -> RestExposure.of(modules)),
                    // Sicherung von MongoDB, config/, templates/ und static/ (§15).
                    new BackupCommandLine(new CloudBackup(layout,
                            CloudBackup.mongoExporter(mongo.database())), layout.backups()),
                    // Zustimmung zur Minecraft-EULA nachträglich ändern (§17).
                    new EulaCommandLine(directory),
                    // `alerts` liest denselben Zustand, den die Überwachung meldet (§20).
                    new AlertsCommandLine(alertService::active));
            log.info("Node-ServiceManager bereit (Runtime {}, Logs {}, Scaling {} s)",
                    config.runtime(), layout.logs(), config.scaling().intervalSeconds());

            verify(healthcheck);

            return new Bootstrap(directory, layout, logFile, configService, mongo, redis, core,
                    services, bridgeRegistrar, controller, scaling, commandLine, healthcheck, shutdown);
        } catch (RuntimeException e) {
            // Nichts offen lassen, wenn ein späterer Schritt fehlschlägt.
            shutdown.shutdown();
            throw e;
        }
    }

    /**
     * @return das Cloud-Verzeichnis
     */
    public Path cloudDirectory() {
        return cloudDirectory;
    }

    /**
     * @return Verzeichnislayout inklusive {@code lib/}, {@code templates/} und {@code running/}
     */
    public CloudLayout layout() {
        return layout;
    }

    /**
     * @return Pfad der aktiven Logdatei
     */
    public Path logFile() {
        return logFile;
    }

    /**
     * @return die aktive Konfiguration
     */
    public CloudConfig config() {
        return configService.current();
    }

    /**
     * @return Service für Hot-Reload der Konfiguration
     */
    public ConfigService configService() {
        return configService;
    }

    /**
     * @return verbundener Mongo-Provider
     */
    public MongoProvider mongo() {
        return mongo;
    }

    /**
     * @return verbundener Redis-Provider
     */
    public RedisProvider redis() {
        return redis;
    }

    /**
     * @return Event-Bus, RPC und Redis-Stores auf der aktiven Verbindung
     */
    public CoreServices core() {
        return core;
    }

    /**
     * @return Service-Manager des Nodes
     */
    public ServiceManager serviceManager() {
        return services;
    }

    /**
     * @return Handshake-/Heartbeat-Endpunkt für Bridge-Plugins
     */
    public BridgeRegistrar bridgeRegistrar() {
        return bridgeRegistrar;
    }

    /**
     * @return Controller-Fassade für CLI und REST
     */
    public ServiceController serviceController() {
        return controller;
    }

    /**
     * @return der periodisch laufende ScalingManager
     */
    public ScalingManager scalingManager() {
        return scaling;
    }

    /**
     * @return Service-Kommandos der CLI
     */
    public ServiceCommandLine commandLine() {
        return commandLine;
    }

    /**
     * @return Healthcheck-Fassade für CLI und REST
     */
    public Healthcheck healthcheck() {
        return healthcheck;
    }

    /**
     * @return Coordinator, bei dem Controller und Node ihre eigenen Schritte registrieren
     */
    public ShutdownCoordinator shutdownCoordinator() {
        return shutdown;
    }

    /**
     * Fährt die Cloud geordnet herunter. Mehrfache Aufrufe sind wirkungslos.
     */
    @Override
    public void close() {
        shutdown.shutdown();
    }

    private static void verify(Healthcheck healthcheck) {
        HealthReport report = healthcheck.check(HEALTHCHECK_TIMEOUT);
        for (ComponentHealth component : report.components().values()) {
            if (component.healthy()) {
                log.info("Healthcheck {}: OK", component.name());
            } else {
                log.error("Healthcheck {}: {}", component.name(), component.detail());
            }
        }
        if (!report.up()) {
            String failures = report.unhealthy().stream()
                    .map(component -> component.name() + " (" + component.detail() + ")")
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("unbekannt");
            throw new IllegalStateException("Healthcheck fehlgeschlagen: " + failures);
        }
    }

    private static ServiceProvisioner provisioner(GroupManager groups, ServiceManager services,
                                                  CoreServices core, CloudLayout layout, CloudConfig config) {
        return new ServiceProvisioner(
                groups::get,
                (group, used) -> core.names().allocate(group, used),
                name -> services.all().stream().anyMatch(info -> info.getName().equals(name)),
                services::start,
                (id, force) -> {
                    ServiceInfo info = services.stop(id, Duration.ofSeconds(10), force);
                    if (info.getState() == ServiceState.STOPPED) {
                        services.remove(id);
                    }
                    return info;
                },
                layout, config,
                new PlatformDownloader(layout.platforms())::ensure,
                // LuckPerms kommt je Plattform aus dem Cache und nutzt Mongo und Redis der Cloud.
                new LuckPermsInstaller(layout.platforms().resolve("luckperms"))::install);
    }

    /**
     * Gleicht Redis gegen die Realität ab, bevor die Bridge Heartbeats annimmt
     * (§7.1): Der Node kennt beim Start keinen Service, also sind alle Einträge in
     * {@code bs:services} Reste des vorherigen Laufs. Ohne diesen Schritt würde die
     * Registry verwaiste Services als {@code ONLINE} zeigen.
     */
    private static void reconcile(CloudLayout layout, CoreServices core,
                                  Collection<String> knownIds, Collection<Integer> knownPorts) {
        Set<String> existingNames;
        try {
            existingNames = ServiceDirectories.names(layout);
        } catch (IOException e) {
            log.warn("Service-Ordner nicht lesbar, Namenszähler bleiben unangetastet: {}", e.getMessage());
            existingNames = Set.of();
        }
        ServiceReconciliation.Report report = new ServiceReconciliation(
                core.services(), core.ports(), core.names()).run(knownIds, knownPorts, existingNames);
        log.info("Abgleich beim Start: {}", report.describe());
    }

    private static ServiceManager nodeServices(CloudConfig config, CloudLayout layout, CoreServices core,
                                               ProcessRuntime processRuntime) {
        RuntimeFactory runtimes = RuntimeFactory.of(processRuntime,
                new DockerRuntime(layout.logs(), core.logs(), config.docker().host()));
        PortSettings ports = config.ports();
        // Der Proxy beginnt bei ports.proxy und weicht bis zum ersten Server-Port aus,
        // wenn der Wunschport von einem Zombie oder einer Reservierung belegt ist.
        PortRange proxyPort = ports.proxyRange();

        return new ServiceManager(
                group -> runtimes.forGroup(config.runtime(), group),
                (group, name) -> {
                    int port = core.ports().allocate(
                            group.getType() == ServiceType.PROXY ? proxyPort : ports.serverRange());
                    return PortLease.of(port, core.ports()::release);
                },
                new TemplatePreparer(layout.templates(), config.proxy().forwardingSecret()),
                core.events()::publish,
                new CrashSupport(config.crash(), new CrashReportWriter(layout.crashReports()),
                        core.events()::publish, CrashHandler.virtualThreads(),
                        group -> log.warn("Gruppe '{}' ist DEGRADED — automatische Neustarts "
                                + "gestoppt, manueller Start nötig", group)),
                // Dieselbe Meldung geht als GroupHealthChanged an den Event-Bus: Module
                // (Discord, Monitoring) benachrichtigen daraus (§12.5, §16).
                core.events()::publish);
    }

    private static ScheduledExecutorService startHeartbeatSweep(ServiceManager services) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bytestorm-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long interval = HEARTBEAT_INTERVAL.toSeconds();
        executor.scheduleWithFixedDelay(() -> {
            try {
                List<ServiceId> timedOut = services.checkHeartbeats();
                if (!timedOut.isEmpty()) {
                    log.warn("Heartbeat-Timeout für {} Service(s): {}", timedOut.size(), timedOut);
                }
            } catch (RuntimeException e) {
                log.error("Heartbeat-Prüfung fehlgeschlagen", e);
            }
        }, interval, interval, TimeUnit.SECONDS);
        return executor;
    }

    /**
     * Runtime-Prüfung für den Bootstrap: Prozess-Runtime braucht ein lauffähiges
     * Java-Binary, Docker-Runtime einen erreichbaren Socket. Die echte
     * Container-Prüfung übernimmt später die {@code DockerRuntime} des Nodes.
     */
    private static HealthProbe runtimeProbe(CloudConfig config) {
        if (config.runtime() == RuntimeType.DOCKER) {
            return dockerProbe(config.docker().host());
        }
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        return () -> {
            if (!Files.isExecutable(bin.resolve("java")) && !Files.isExecutable(bin.resolve("java.exe"))) {
                throw new IllegalStateException("Java-Runtime nicht ausführbar: " + bin);
            }
        };
    }

    /**
     * Übersetzt den internen Healthcheck in den öffentlichen Typ der API.
     *
     * @param healthcheck Fassade aus Mongo, Redis und Runtime
     * @return der Bericht für Module und Monitoring-Endpunkte
     */
    private static CloudHealth toCloudHealth(Healthcheck healthcheck) {
        HealthReport report = healthcheck.check(HEALTHCHECK_TIMEOUT);
        return new CloudHealth(report.up(), report.components().values().stream()
                .map(component -> new CloudHealth.Component(
                        component.name(), component.healthy(), component.detail()))
                .toList());
    }

    private static String timed(Runnable probe) {
        long started = System.nanoTime();
        probe.run();
        return "antwortet in " + ((System.nanoTime() - started) / 1_000_000) + " ms";
    }

    private static HealthProbe dockerProbe(String host) {
        return () -> {
            if (host.startsWith("unix://")) {
                Path socket = Path.of(host.substring("unix://".length()));
                try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                    channel.connect(UnixDomainSocketAddress.of(socket));
                } catch (IOException | UnsupportedOperationException e) {
                    throw new IllegalStateException("Docker-Socket nicht erreichbar: " + socket, e);
                }
                return;
            }
            if (host.startsWith("tcp://")) {
                int colon = host.lastIndexOf(':');
                if (colon < 0) {
                    throw new IllegalStateException("Docker-Host ohne Port: " + host);
                }
                String hostName = host.substring("tcp://".length(), colon);
                int port = Integer.parseInt(host.substring(colon + 1));
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(hostName, port), 1_000);
                } catch (IOException e) {
                    throw new IllegalStateException("Docker nicht erreichbar unter " + host, e);
                }
                return;
            }
            throw new IllegalStateException("Nicht unterstützter Docker-Host: " + host);
        };
    }

    /**
     * Startet die Cloud und bedient an einem Terminal die interaktive Konsole.
     * Ohne Terminal blockiert der Prozess bis zum Herunterfahren (Strg+C/SIGTERM).
     *
     * @param args optional {@code --dir <Pfad>}
     */
    public static void main(String[] args) {
        if (LauncherArguments.wantsHelp(args)) {
            System.out.println(LauncherArguments.USAGE);
            return;
        }

        Bootstrap bootstrap;
        try {
            Path directory = LauncherArguments.cloudDirectory(args);
            ensureConfigured(directory);
            bootstrap = start(directory);
        } catch (ConfigException e) {
            System.err.println(Launcher.errorLine("Konfiguration ungültig — " + e.getMessage()));
            System.exit(1);
            return;
        } catch (RuntimeException e) {
            System.err.println(Launcher.errorLine("Start fehlgeschlagen — " + e.getMessage()));
            System.exit(1);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(bootstrap::close, "bytestorm-shutdown"));
        log.info("ByteStorm bereit — Strg+C beendet die Cloud");
        if (System.console() == null) {
            log.info("Kein Terminal erkannt — interaktive CLI deaktiviert");
            awaitShutdown();
            return;
        }

        try (ServiceConsole console = new ServiceConsole(bootstrap.commandLine(),
                bootstrap.serviceController(),
                bootstrap.layout().logs().resolve(ServiceConsole.HISTORY_FILE_NAME));
             ConsoleLogBridge logs = ConsoleLogBridge.attach(console::printAbove)) {
            console.run();
        } catch (IOException e) {
            log.warn("CLI-Konsole nicht verfügbar: {} — Strg+C beendet die Cloud", e.getMessage());
            awaitShutdown();
            return;
        }
        log.info("CLI-Konsole beendet — fahre Cloud herunter");
        bootstrap.close();
    }

    /**
     * Führt bei fehlender Konfiguration den Erststart-Wizard aus (§5.1). Ohne
     * Terminal wird abgebrochen, statt in einer unbeaufsichtigten Umgebung auf
     * Eingaben zu warten.
     *
     * @param directory Cloud-Verzeichnis
     * @throws ConfigException wenn die Einrichtung nicht abgeschlossen werden kann
     */
    static void ensureConfigured(Path directory) throws ConfigException {
        Path configFile = directory.resolve(CONFIG_FILE);
        if (Files.exists(configFile)) {
            return;
        }
        if (System.console() == null) {
            throw new ConfigException("Konfigurationsdatei fehlt: " + configFile
                    + " — der Erststart-Wizard benötigt ein Terminal; Datei bitte manuell anlegen");
        }

        Logging.configure(directory);
        log.info("Keine Konfiguration gefunden — starte Erststart-Wizard");
        try (JLineWizardIo io = new JLineWizardIo()) {
            new FirstRunWizard(io, new DefaultConnectionTester(), GroupSetup::create,
                    DefaultModules::create).run(directory);
        } catch (WizardAbortedException e) {
            throw new ConfigException("Einrichtung abgebrochen: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ConfigException("Konfiguration konnte nicht geschrieben werden: " + e.getMessage(), e);
        }
    }

    private static void awaitShutdown() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
