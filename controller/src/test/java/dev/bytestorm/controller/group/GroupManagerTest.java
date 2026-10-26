package dev.bytestorm.controller.group;

import dev.bytestorm.core.config.PortSettings;
import dev.bytestorm.core.mongo.repository.GroupStore;
import dev.bytestorm.core.redis.GroupCache;
import dev.bytestorm.proto.LoadRule;
import dev.bytestorm.proto.PlayerCountRule;
import dev.bytestorm.proto.ScheduleRule;
import dev.bytestorm.proto.ScalingRule;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupManagerTest {

    private final Map<String, ServiceGroup> stored = new LinkedHashMap<>();
    private final Map<String, ServiceGroup> cached = new LinkedHashMap<>();
    private final List<String> invalidated = new ArrayList<>();
    private final Set<String> templates = Set.of("Lobby", "Survival");

    private int finds;
    private GroupManager manager;

    @BeforeEach
    void setUp() {
        manager = new GroupManager(new FakeStore(), new FakeCache(),
                new PortSettings(25565, 30000), templates::contains);
    }

    @Test
    void createStoresTheGroupAndDropsTheCache() throws Exception {
        cached.put("Lobby", group("Lobby").build());

        ServiceGroup created = manager.create(group("Lobby").build());

        assertEquals("Lobby", created.getName());
        assertTrue(stored.containsKey("Lobby"));
        assertEquals(List.of("Lobby"), invalidated, "Chache wird geleert, damit nichts Altes hängen bleibt");
    }

    @Test
    void reloadCacheFillsTheCacheFromStorage() {
        // Der Cache kennt nur eine veraltete Kopie und einen Eintrag, den es nicht mehr gibt.
        cached.put("Lobby", group("Lobby").setMaxServices(1).build());
        cached.put("Alt", group("Alt").build());
        stored.put("Lobby", group("Lobby").build());
        stored.put("Survival", group("Survival").build());

        int count = manager.reloadCache();

        assertEquals(2, count);
        assertEquals(Set.of("Lobby", "Survival"), cached.keySet(), "Alt-Eintrag und alte Fassung sind weg");
        assertEquals(group("Lobby").build(), cached.get("Lobby"));
    }

    @Test
    void createRejectsAnExistingName() {
        stored.put("Lobby", group("Lobby").build());

        GroupValidationException error = assertThrows(GroupValidationException.class,
                () -> manager.create(group("Lobby").build()));

        assertTrue(error.getMessage().contains("existiert bereits"), error.getMessage());
    }

    @Test
    void updateRequiresAnExistingGroup() {
        GroupValidationException error = assertThrows(GroupValidationException.class,
                () -> manager.update(group("Lobby").build()));

        assertTrue(error.getMessage().contains("existiert nicht"), error.getMessage());
    }

    @Test
    void updateReplacesTheStoredGroup() throws Exception {
        stored.put("Lobby", group("Lobby").build());

        ServiceGroup updated = manager.update(group("Lobby").setMaxServices(5).build());

        assertEquals(5, updated.getMaxServices());
        assertEquals(5, stored.get("Lobby").getMaxServices());
        assertEquals(List.of("Lobby"), invalidated);
    }

    @Test
    void minimumAboveMaximumIsRejected() {
        List<String> problems = manager.validate(group("Lobby")
                .setMinServices(4).setMaxServices(2).build(), true);

        assertTrue(problems.stream().anyMatch(p -> p.contains("minServices")), problems.toString());
    }

    @Test
    void unlimitedMaximumAllowsAnyMinimum() {
        assertTrue(manager.validate(group("Lobby").setMinServices(7).setMaxServices(0).build(), true)
                .isEmpty());
    }

    @Test
    void namePlatformAndPlayerLimitAreChecked() {
        assertFalse(manager.validate(group("Lobby 1").build(), true).isEmpty());
        assertFalse(manager.validate(group("Lobby").setPlatform(" ").build(), true).isEmpty());
        assertFalse(manager.validate(group("Lobby").setPlatform("pa/per").build(), true).isEmpty());
        assertFalse(manager.validate(group("Lobby").setMaxPlayers(0).build(), true).isEmpty());
    }

    @Test
    void proxyGroupsKeepTheSingleProxyPort() {
        ServiceGroup proxy = group("Proxy").setType(ServiceType.PROXY).setMaxServices(3).build();

        List<String> problems = manager.validate(proxy, true);

        assertTrue(problems.stream().anyMatch(p -> p.contains("ports.proxy")), problems.toString());
    }

    @Test
    void portRangeMustFitTheGroup() {
        GroupManager tight = new GroupManager(new FakeStore(), new FakeCache(),
                new PortSettings(25565, 65100), templates::contains);

        List<String> problems = tight.validate(group("Lobby").setMaxServices(1000).build(), true);

        assertTrue(problems.stream().anyMatch(p -> p.contains("Portbereich")), problems.toString());
    }

    @Test
    void templatesMustExistOrBeAFixedLayer() {
        assertTrue(manager.validate(group("Lobby").addTemplates("ALL").addTemplates("Lobby").build(), true)
                .isEmpty());

        List<String> problems = manager.validate(group("Lobby").addTemplates("Fehlt").build(), true);
        assertTrue(problems.stream().anyMatch(p -> p.contains("existiert nicht in templates/")),
                problems.toString());
        assertFalse(manager.validate(group("Lobby").addTemplates("../etc").build(), true).isEmpty());
    }

    @Test
    void javaVersionMustBeAValidMajor() {
        assertTrue(manager.validate(group("Lobby").setJavaVersion(21).build(), true).isEmpty());
        assertFalse(manager.validate(group("Lobby").setJavaVersion(-1).build(), true).isEmpty());
    }

    @Test
    void activeRulesAreValidated() {
        List<String> problems = manager.validate(group("Lobby")
                .addScalingRules(rule("cpu", ScalingRule.newBuilder()
                        .setLoad(LoadRule.newBuilder().setMaxHostCpu(150).build())))
                .addScalingRules(rule("zeit", ScalingRule.newBuilder()
                        .setSchedule(ScheduleRule.newBuilder()
                                .setCron("kein cron")
                                .setTimezone("Mars/Olympus")
                                .build())))
                .addScalingRules(rule("spieler", ScalingRule.newBuilder()
                        .setPlayerCount(PlayerCountRule.newBuilder()
                                .setScaleUpUtilization(0.5)
                                .setIdleUtilization(0.9)
                                .build())))
                .build(), true);

        assertEquals(4, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("maxHostCpu")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("Cron-Ausdruck")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("Zeitzone")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("idleUtilization")), problems.toString());
    }

    @Test
    void ruleIdsMustBePresentAndUnique() {
        assertFalse(manager.validate(group("Lobby").addScalingRules(
                ScalingRule.newBuilder().setEnabled(true)
                        .setLoad(LoadRule.newBuilder().setMaxHostCpu(50).build())).build(), true)
                .isEmpty());

        List<String> problems = manager.validate(group("Lobby")
                .addScalingRules(rule("a", ScalingRule.newBuilder()
                        .setLoad(LoadRule.newBuilder().setMaxHostCpu(50).build())))
                .addScalingRules(rule("a", ScalingRule.newBuilder()
                        .setLoad(LoadRule.newBuilder().setMaxHostMemory(50).build())))
                .build(), true);

        assertTrue(problems.stream().anyMatch(p -> p.contains("doppelt")), problems.toString());
    }

    @Test
    void disabledRulesDoNotBlockSaving() {
        ServiceGroup group = group("Lobby")
                .addScalingRules(ScalingRule.newBuilder()
                        .setEnabled(false)
                        .setSchedule(ScheduleRule.newBuilder().setCron("kaputt").build()))
                .build();

        assertTrue(manager.validate(group, true).isEmpty());
    }

    @Test
    void getReadsThroughTheCache() throws Exception {
        manager.create(group("Lobby").build());
        int afterCreate = finds;

        manager.get("Lobby");

        assertEquals(afterCreate + 1, finds, "der erste Zugriff liest aus MongoDB");
        assertTrue(cached.containsKey("Lobby"));
        manager.get("Lobby");
        assertEquals(afterCreate + 1, finds, "der zweite Zugriff kommt aus dem Cache");
    }

    @Test
    void setMaintenanceTogglesOnlyTheFlag() throws Exception {
        manager.create(group("Lobby").build());

        ServiceGroup maintenance = manager.setMaintenance("Lobby", true);

        assertTrue(maintenance.getMaintenance());
        assertTrue(stored.get("Lobby").getMaintenance());
        assertEquals(List.of("Lobby", "Lobby"), invalidated);
    }

    @Test
    void setMaintenanceRejectsUnknownGroups() {
        GroupValidationException error = assertThrows(GroupValidationException.class,
                () -> manager.setMaintenance("Fehlt", true));

        assertTrue(error.getMessage().contains("existiert nicht"), error.getMessage());
    }

    @Test
    void deleteReportsWhetherAGroupWasRemoved() throws Exception {
        manager.create(group("Lobby").build());

        assertTrue(manager.delete("Lobby"));
        assertFalse(manager.delete("Lobby"));
        assertFalse(stored.containsKey("Lobby"));
    }

    @Test
    void allIsSortedByName() throws Exception {
        manager.create(group("Survival").build());
        manager.create(group("Lobby").build());

        assertEquals(List.of("Lobby", "Survival"),
                manager.all().stream().map(ServiceGroup::getName).toList());
    }

    private static ScalingRule.Builder rule(String id, ScalingRule.Builder builder) {
        return builder.setId(id).setEnabled(true);
    }

    private static ServiceGroup.Builder group(String name) {
        return ServiceGroup.newBuilder()
                .setName(name)
                .setType(ServiceType.SERVER)
                .setPlatform("paper")
                .setMode(ServiceMode.DYNAMIC)
                .setMinServices(1)
                .setMaxServices(3)
                .setMaxPlayers(100)
                .setMemory(2048);
    }

    private final class FakeStore implements GroupStore {

        @Override
        public List<ServiceGroup> findAll() {
            return List.copyOf(stored.values());
        }

        @Override
        public Optional<ServiceGroup> find(String name) {
            finds++;
            return Optional.ofNullable(stored.get(name));
        }

        @Override
        public void upsert(ServiceGroup group) {
            stored.put(group.getName(), group);
        }

        @Override
        public boolean delete(String name) {
            return stored.remove(name) != null;
        }

        @Override
        public boolean setMaintenance(String name, boolean maintenance) {
            ServiceGroup group = stored.get(name);
            if (group == null) {
                return false;
            }
            stored.put(name, group.toBuilder().setMaintenance(maintenance).build());
            return true;
        }
    }

    private final class FakeCache implements GroupCache {

        @Override
        public Optional<ServiceGroup> get(String name) {
            return Optional.ofNullable(cached.get(name));
        }

        @Override
        public void put(ServiceGroup group) {
            cached.put(group.getName(), group);
        }

        @Override
        public boolean invalidate(String name) {
            invalidated.add(name);
            return cached.remove(name) != null;
        }

        @Override
        public void clear() {
            cached.clear();
        }
    }
}
