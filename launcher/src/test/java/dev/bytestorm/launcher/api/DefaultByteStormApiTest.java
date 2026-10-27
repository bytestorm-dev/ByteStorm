package dev.bytestorm.launcher.api;

import dev.bytestorm.api.ByteStorm;
import dev.bytestorm.api.event.Subscription;
import dev.bytestorm.api.module.Modules;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.controller.ServiceController;
import dev.bytestorm.controller.group.GroupManager;
import dev.bytestorm.controller.group.GroupScaler;
import dev.bytestorm.controller.scaling.ScalingActions;
import dev.bytestorm.controller.scaling.ScalingDecision;
import dev.bytestorm.controller.template.TemplateManager;
import dev.bytestorm.core.config.PortSettings;
import dev.bytestorm.core.mongo.model.StoredPlayer;
import dev.bytestorm.core.mongo.repository.GroupStore;
import dev.bytestorm.core.redis.GroupCache;
import dev.bytestorm.node.lifecycle.ServiceLifecycleException;
import dev.bytestorm.proto.ModuleInfo;
import dev.bytestorm.proto.ModuleState;
import dev.bytestorm.proto.ModuleStateChanged;
import dev.bytestorm.proto.PlayerInfo;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.StartServiceResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultByteStormApiTest {

    @TempDir
    Path temp;

    private final UUID onlineUuid = UUID.randomUUID();
    private final UUID offlineUuid = UUID.randomUUID();
    private final List<String> calls = new ArrayList<>();
    private final Map<String, ServiceGroup> storedGroups = new LinkedHashMap<>();
    private final Map<String, ModuleInfo> modules = new LinkedHashMap<>();

    private FakeController controller;
    private FakeScaler scaler;
    private DefaultByteStormApi api;

    @BeforeEach
    void setUp() throws Exception {
        controller = new FakeController();
        GroupManager groups = new GroupManager(new FakeGroupStore(), new FakeGroupCache(),
                new PortSettings(25565, 30000), name -> true);
        storedGroups.put("Lobby", group("Lobby").build());
        TemplateManager templates = new TemplateManager(Files.createDirectories(temp.resolve("templates")),
                name -> Optional.of(serviceDirectory(name)));
        Files.createDirectories(temp.resolve("running").resolve("Lobby-1"));
        modules.put("rest", ModuleInfo.newBuilder().setName("rest").setVersion("1.0")
                .setEnabled(true).setState(ModuleState.ENABLED).build());

        scaler = new FakeScaler();
        api = new DefaultByteStormApi(controller, scaler, groups, fakeScaler(),
                playerAccess(), templates, new FakeModules(), new FakeEvents());
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    @Test
    void servicesReadFromTheController() {
        service("Lobby-1", "Lobby");

        assertEquals(1, api.services().all().size());
        assertEquals("Lobby-1", api.services().byName("Lobby-1").orElseThrow().getName());
        assertEquals(1, api.services().byGroup("Lobby").size());
        assertTrue(api.services().byId(controller.services.get(0).getId()).isPresent());
        assertTrue(api.services().byId("unbekannt").isEmpty());
    }

    @Test
    void startReturnsSuccessOrTheErrorInTheResponse() {
        StartServiceResponse ok = api.services().start("Lobby").join();

        assertTrue(ok.getSuccess());
        assertEquals("Lobby-1", ok.getService().getName());
        assertEquals(List.of("start:Lobby"), calls);

        DefaultByteStormApi failing = new DefaultByteStormApi(controller, new ScalingActions() {
            @Override
            public ServiceInfo start(String group, Map<String, String> properties)
                    throws ServiceLifecycleException {
                throw new ServiceLifecycleException("Gruppe fehlt");
            }

            @Override
            public ServiceInfo stop(ServiceId id, boolean force) {
                return null;
            }
        }, groupManager(), fakeScaler(), playerAccess(), templateManager(),
                new FakeModules(), new FakeEvents());
        try {
            StartServiceResponse error = failing.services().start("Fehlt").join();
            assertFalse(error.getSuccess());
            assertTrue(error.getError().contains("Gruppe fehlt"), error.getError());
        } finally {
            failing.close();
        }
    }

    @Test
    void startPassesThePropertiesOnToTheProvisioner() {
        api.services().start("Lobby", Map.of("template", "lobby", "memory", "4096")).join();

        assertEquals(Map.of("template", "lobby", "memory", "4096"), scaler.properties);
        assertEquals(List.of("start:Lobby"), calls);

        api.services().start("Lobby").join();
        assertEquals(Map.of(), scaler.properties, "ohne Angabe startet der Service mit den Gruppen-Properties");
        assertThrows(NullPointerException.class, () -> api.services().start("Lobby", null));
    }

    @Test
    void stopRestartAndExecuteGoThroughTheControllerWithAParsedId() {
        ServiceInfo info = service("Lobby-1", "Lobby");
        ServiceId id = ServiceId.parse(info.getId());

        api.services().stop(info.getId(), true).join();
        api.services().restart(info.getId()).join();
        api.services().execute(info.getId(), "say hallo").join();

        assertEquals(List.of("stop:" + id + ":true", "restart:" + id, "execute:" + id + ":say hallo"), calls);
    }

    @Test
    void invalidServiceIdsFailTheFuture() {
        CompletionException error = assertThrows(CompletionException.class,
                () -> api.services().stop("keine-uuid", false).join());

        assertTrue(error.getCause() instanceof IllegalArgumentException, "UUID-Fehler muss durchgereicht werden");
    }

    @Test
    void groupsUseManagerAndScaler() {
        api.groups().create(group("Survival").build()).join();

        assertEquals(Set.of("Lobby", "Survival"), storedGroups.keySet());
        assertEquals("Survival", api.groups().get("Survival").orElseThrow().getName());

        api.groups().setMaintenance("Survival", true).join();
        assertTrue(storedGroups.get("Survival").getMaintenance());

        api.groups().scale("Lobby", 4).join();
        assertEquals(List.of("scale:Lobby:4"), calls);

        CompletionException unknown = assertThrows(CompletionException.class,
                () -> api.groups().delete("Fehlt").join());
        assertTrue(unknown.getCause().getMessage().contains("Unbekannte Gruppe"), unknown.getCause().getMessage());
    }

    @Test
    void playersPreferTheLiveStateAndFallBackToTheHistory() {
        PlayerInfo live = PlayerInfo.newBuilder().setUuid(onlineUuid.toString())
                .setName("Notch").setProxy("Proxy-1").setServer("Lobby-1").build();

        assertEquals(1, api.players().online().size());
        assertEquals("Lobby-1", api.players().byUuid(onlineUuid).orElseThrow().getServer());
        assertEquals("Lobby-1", api.players().byName("notch").orElseThrow().getServer());
        // Der zweite Eintrag kommt nur aus der Historie.
        assertEquals(2, api.players().all().size());
        assertEquals("Lobby-3", api.players().byUuid(offlineUuid).orElseThrow().getServer());

        assertEquals(Duration.ofHours(3), api.players().playtime(offlineUuid).join());
        assertEquals(Duration.ZERO, api.players().playtime(UUID.randomUUID()).join());
    }

    @Test
    void playerActionsResolveTheNameAndReportUnknownPlayers() {
        api.players().connect(onlineUuid, "Survival-1").join();
        api.players().kick(onlineUuid, "Wartung").join();

        assertEquals(List.of("connect:Notch:Survival-1", "kick:Notch:Wartung"), calls);

        CompletionException unknown = assertThrows(CompletionException.class,
                () -> api.players().connect(UUID.randomUUID(), "Survival-1").join());
        assertTrue(unknown.getCause().getMessage().contains("Unbekannter Spieler"), unknown.getCause().getMessage());
    }

    @Test
    void templatesMapEntriesToProtoAndRejectUnknownNames() {
        ServiceInfo lobby = service("Lobby-1", "Lobby");
        api.templates().create("Events").join();

        assertTrue(api.templates().list().stream().anyMatch(info -> info.getName().equals("Events")));
        assertTrue(api.templates().get("Events").orElseThrow().getLastModified() > 0);
        assertTrue(api.templates().get("Fehlt").isEmpty());

        // Der Sync spiegelt den Service-Ordner ins Template (§10).
        api.templates().sync(lobby.getId(), "Events").join();

        CompletionException unknown = assertThrows(CompletionException.class,
                () -> api.templates().delete("Fehlt").join());
        assertTrue(unknown.getCause().getMessage().contains("Unbekanntes Template"), unknown.getCause().getMessage());

        CompletionException unknownService = assertThrows(CompletionException.class,
                () -> api.templates().sync(ServiceId.random().toString(), "Events").join());
        assertTrue(unknownService.getCause().getMessage().contains("Unbekannter Service"),
                unknownService.getCause().getMessage());
    }

    @Test
    void modulesAndEventsAreForwardedAndTheApiCanBeInstalled() {
        assertEquals(1, api.modules().list().size());
        assertTrue(api.modules().isEnabled("rest"));
        assertTrue(api.modules().disable("rest").isDone());
        assertEquals(List.of("disable:rest"), calls);

        Subscription subscription = api.events().subscribe(ModuleStateChanged.class, event -> {
        });
        assertTrue(subscription.isActive());
        subscription.close();
        assertFalse(subscription.isActive());
        assertEquals(List.of("disable:rest", "subscribe:ModuleStateChanged"), calls);

        // Andere Tests können bereits eine Implementierung installiert haben.
        ByteStorm.uninstall();
        ByteStorm.install(api);
        assertSame(api, ByteStorm.api());
        assertTrue(ByteStorm.isInstalled());
        ByteStorm.uninstall();
        assertFalse(ByteStorm.isInstalled());
    }

    private DefaultByteStormApi.PlayerAccess playerAccess() {
        PlayerInfo live = PlayerInfo.newBuilder().setUuid(onlineUuid.toString())
                .setName("Notch").setProxy("Proxy-1").setServer("Lobby-1").build();
        StoredPlayer stored = new StoredPlayer(offlineUuid, "Herobrine", Duration.ofHours(3),
                Instant.EPOCH, Instant.EPOCH, "Lobby-3");
        Map<UUID, StoredPlayer> byUuid = Map.of(offlineUuid, stored);
        return new DefaultByteStormApi.PlayerAccess(
                () -> List.of(live),
                name -> Optional.ofNullable(byUuid.values().stream()
                        .filter(player -> player.name().equalsIgnoreCase(name))
                        .findFirst().orElse(null)),
                uuid -> Optional.ofNullable(byUuid.get(uuid)),
                () -> List.copyOf(byUuid.values()),
                (player, target) -> {
                    calls.add("connect:" + player + ":" + target);
                    return "Proxy-1";
                },
                (player, reason) -> {
                    calls.add("kick:" + player + ":" + reason);
                    return "Proxy-1";
                });
    }

    private Path serviceDirectory(String name) {
        try {
            return Files.createDirectories(temp.resolve("running").resolve(name));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private GroupScaler fakeScaler() {
        return (group, target) -> {
            calls.add("scale:" + group + ":" + target);
            return List.<ScalingDecision>of();
        };
    }

    private GroupManager groupManager() {
        return new GroupManager(new FakeGroupStore(), new FakeGroupCache(),
                new PortSettings(25565, 30000), name -> true);
    }

    private TemplateManager templateManager() {
        return new TemplateManager(temp.resolve("templates"), name -> Optional.empty());
    }

    private ServiceInfo service(String name, String group) {
        ServiceInfo info = ServiceInfo.newBuilder()
                .setId(ServiceId.random().toString())
                .setName(name)
                .setGroup(group)
                .build();
        controller.services.add(info);
        return info;
    }

    private static ServiceGroup.Builder group(String name) {
        return ServiceGroup.newBuilder()
                .setName(name)
                .setType(dev.bytestorm.proto.ServiceType.SERVER)
                .setPlatform("paper")
                .setMode(dev.bytestorm.proto.ServiceMode.DYNAMIC)
                .setMinServices(1)
                .setMaxServices(3)
                .setMaxPlayers(100)
                .setMemory(2048);
    }

    /** Abos laufen im Test über den Event-Bus-Ersatz; die generische Methode geht nicht als Lambda. */
    private final class FakeEvents implements DefaultByteStormApi.EventRegistrar {

        @Override
        public <T extends com.google.protobuf.Message> Subscription register(Class<T> type,
                                                                            java.util.function.Predicate<T> filter,
                                                                            java.util.function.Consumer<T> listener) {
            calls.add("subscribe:" + type.getSimpleName());
            return new FakeSubscription();
        }
    }

    /** Start/Stop über den Provisioner (§9): startet jeden Service als {@code <Gruppe>-1}. */
    private final class FakeScaler implements ScalingActions {

        private Map<String, String> properties = Map.of();

        @Override
        public ServiceInfo start(String group, Map<String, String> properties) {
            calls.add("start:" + group);
            this.properties = Map.copyOf(properties);
            return ServiceInfo.newBuilder()
                    .setId(ServiceId.random().toString())
                    .setName(group + "-1")
                    .setGroup(group)
                    .build();
        }

        @Override
        public ServiceInfo stop(ServiceId id, boolean force) {
            calls.add("stop:" + id + ":" + force);
            return null;
        }
    }

    private static final class FakeSubscription implements Subscription {

        private boolean active = true;

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void unsubscribe() {
            active = false;
        }
    }

    private final class FakeModules implements Modules {

        @Override
        public Collection<ModuleInfo> list() {
            return List.copyOf(modules.values());
        }

        @Override
        public Optional<ModuleInfo> get(String name) {
            return Optional.ofNullable(modules.get(name));
        }

        @Override
        public boolean isEnabled(String name) {
            return get(name).map(ModuleInfo::getEnabled).orElse(false);
        }

        @Override
        public java.util.Map<String, String> settings(String name) {
            return java.util.Map.of();
        }

        @Override
        public void setSetting(String name, String key, String value) {
            calls.add("set:" + name + "." + key + "=" + value);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> enable(String name) {
            calls.add("enable:" + name);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> disable(String name) {
            calls.add("disable:" + name);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }

    private final class FakeController implements ServiceController {

        private final List<ServiceInfo> services = new ArrayList<>();

        @Override
        public ServiceInfo start(dev.bytestorm.node.lifecycle.ServiceDefinition definition) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ServiceInfo start(ServiceId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ServiceInfo stop(ServiceId id, Duration timeout, boolean force) {
            calls.add("stop:" + id + ":" + force);
            return services.get(0);
        }

        @Override
        public ServiceInfo restart(ServiceId id, Duration timeout) {
            calls.add("restart:" + id);
            return services.get(0);
        }

        @Override
        public ServiceInfo kill(ServiceId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ServiceInfo reapply(ServiceId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ServiceInfo online(ServiceId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void heartbeat(ServiceId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ServiceId> checkHeartbeats() {
            return List.of();
        }

        @Override
        public List<ServiceInfo> services() {
            return List.copyOf(services);
        }

        @Override
        public List<ServiceInfo> byGroup(String group) {
            return services.stream().filter(info -> info.getGroup().equals(group)).toList();
        }

        @Override
        public Optional<ServiceInfo> byName(String name) {
            return services.stream().filter(info -> info.getName().equals(name)).findFirst();
        }

        @Override
        public void sendCommand(ServiceId id, String command) {
            calls.add("execute:" + id + ":" + command);
        }

        @Override
        public List<String> logs(ServiceId id) {
            return List.of();
        }

        @Override
        public Set<String> degradedGroups() {
            return Set.of();
        }
    }

    private final class FakeGroupStore implements GroupStore {

        @Override
        public List<ServiceGroup> findAll() {
            return List.copyOf(storedGroups.values());
        }

        @Override
        public Optional<ServiceGroup> find(String name) {
            return Optional.ofNullable(storedGroups.get(name));
        }

        @Override
        public void upsert(ServiceGroup group) {
            storedGroups.put(group.getName(), group);
        }

        @Override
        public boolean delete(String name) {
            return storedGroups.remove(name) != null;
        }

        @Override
        public boolean setMaintenance(String name, boolean maintenance) {
            ServiceGroup group = storedGroups.get(name);
            if (group == null) {
                return false;
            }
            storedGroups.put(name, group.toBuilder().setMaintenance(maintenance).build());
            return true;
        }
    }

    private static final class FakeGroupCache implements GroupCache {

        @Override
        public Optional<ServiceGroup> get(String name) {
            return Optional.empty();
        }

        @Override
        public void put(ServiceGroup group) {
            // kein Cache im Test
        }

        @Override
        public boolean invalidate(String name) {
            return false;
        }

        @Override
        public void clear() {
            // kein Cache im Test
        }
    }
}
