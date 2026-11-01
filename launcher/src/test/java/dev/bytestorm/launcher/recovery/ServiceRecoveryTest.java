package dev.bytestorm.launcher.recovery;

import dev.bytestorm.api.service.ServiceHandle;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.core.config.CrashSettings;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.launcher.layout.CloudLayout;
import dev.bytestorm.node.crash.CrashReportWriter;
import dev.bytestorm.node.crash.CrashSupport;
import dev.bytestorm.node.lifecycle.ServiceManager;
import dev.bytestorm.node.lifecycle.ServicePid;
import dev.bytestorm.node.lifecycle.ServicePreparer;
import dev.bytestorm.node.runtime.AdoptableRuntime;
import dev.bytestorm.node.runtime.PortLease;
import dev.bytestorm.node.runtime.ServiceLaunch;
import dev.bytestorm.node.runtime.ServiceRuntime;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceRecoveryTest {

    private static final RedisSettings REDIS = new RedisSettings("redis://localhost:6379", "secret");

    @TempDir
    Path temp;

    @Test
    void adoptsTheProcessOfAnExistingGroup() throws Exception {
        CloudLayout layout = layout();
        Path directory = Files.createDirectories(layout.staticServices().resolve("Proxy-1"));
        entry(layout, "Proxy-1", "Proxy", 25565, ProcessHandle.current().pid()).write(directory);
        ServiceManager services = manager(layout, new FakeRuntime());
        List<Integer> released = new ArrayList<>();

        ServiceRecovery.Report report = new ServiceRecovery(layout, name -> Optional.of(group("Proxy")),
                REDIS, released::add, services).run();

        assertEquals(List.of("Proxy-1"), report.adopted());
        assertEquals(Set.of(25565), report.adoptedPorts());
        assertEquals(1, report.adoptedIds().size());
        ServiceInfo info = services.all().get(0);
        assertEquals("Proxy-1", info.getName());
        assertEquals("Proxy", info.getGroup());
        assertEquals(ServiceState.STARTING, info.getState(),
                "der übernommene Service muss noch auf den Bridge-Handshake warten");
        assertEquals(25565, info.getPort());
        assertTrue(Files.isRegularFile(ServicePid.fileIn(directory)),
                "der laufende Prozess behält seinen Eintrag für den nächsten Start");
        assertTrue(released.isEmpty(), "ein übernommener Port bleibt reserviert");
    }

    @Test
    void reapsAnEntryWhoseProcessIsGone() throws Exception {
        CloudLayout layout = layout();
        Path directory = Files.createDirectories(
                layout.running().resolve("Lobby-1-7dcd75e9-d1db-4dad-86e4-35a82839728c"));
        entry(layout, "Lobby-1", "Lobby", 30000, finishedPid()).write(directory);
        ServiceManager services = manager(layout, new FakeRuntime());
        List<Integer> released = new ArrayList<>();

        ServiceRecovery.Report report = new ServiceRecovery(layout, name -> Optional.of(group("Lobby")),
                REDIS, released::add, services).run();

        assertEquals(List.of("Lobby-1"), report.reaped());
        assertTrue(report.adopted().isEmpty());
        assertTrue(report.adoptedPorts().isEmpty());
        assertFalse(Files.exists(ServicePid.fileIn(directory)), "der Eintrag wird entfernt");
        assertEquals(List.of(30000), released, "die alte Reservierung wird freigegeben");
        assertTrue(services.all().isEmpty());
    }

    @Test
    void anUnreadableEntryIsReportedAndNotAdopted() throws Exception {
        CloudLayout layout = layout();
        Path directory = Files.createDirectories(layout.staticServices().resolve("Lobby-1"));
        Files.writeString(ServicePid.fileIn(directory), "kein-schluessel=wert\n");
        ServiceManager services = manager(layout, new FakeRuntime());

        ServiceRecovery.Report report = new ServiceRecovery(layout, name -> Optional.of(group("Lobby")),
                REDIS, port -> {
        }, services).run();

        assertEquals(List.of("Lobby-1"), report.skipped());
        assertTrue(services.all().isEmpty());
        assertFalse(Files.exists(ServicePid.fileIn(directory)));
    }

    @Test
    void aFolderWithoutEntryIsIgnored() throws Exception {
        CloudLayout layout = layout();
        Files.createDirectories(layout.staticServices().resolve("Lobby-1"));
        ServiceManager services = manager(layout, new FakeRuntime());

        ServiceRecovery.Report report = new ServiceRecovery(layout, name -> Optional.of(group("Lobby")),
                REDIS, port -> {
        }, services).run();

        assertTrue(report.isEmpty());
        assertTrue(services.all().isEmpty());
    }

    private CloudLayout layout() throws Exception {
        CloudLayout layout = new CloudLayout(temp);
        layout.create();
        return layout;
    }

    private ServiceManager manager(CloudLayout layout, ServiceRuntime runtime) {
        return new ServiceManager(
                group -> runtime,
                (group, name) -> PortLease.fixed(25565),
                new ServicePreparer() {
                    @Override
                    public void prepare(dev.bytestorm.node.lifecycle.ServiceDefinition definition) {
                    }
                },
                event -> {
                },
                new CrashSupport(new CrashSettings(3, List.of(5)),
                        new CrashReportWriter(layout.crashReports()),
                        event -> {
                        },
                        (task, delay) -> {
                        },
                        group -> {
                        }));
    }

    private static ServicePid entry(CloudLayout layout, String name, String group, int port, long pid) {
        return new ServicePid(ServiceId.random().toString(), name, group, port, pid, RuntimeType.PROCESS,
                System.currentTimeMillis(), layout.platforms().resolve("paper.jar").toString(), "");
    }

    private static ServiceGroup group(String name) {
        return ServiceGroup.newBuilder()
                .setName(name)
                .setType(ServiceType.SERVER)
                .setMode(ServiceMode.STATIC)
                .setMinServices(1)
                .setMaxServices(1)
                .setMaxPlayers(100)
                .build();
    }

    /**
     * Startet einen Prozess, der sofort endet, und liefert seine PID: So entsteht
     * eine Prozess-ID, die garantiert nicht mehr lebt.
     */
    private static long finishedPid() throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        Process process = new ProcessBuilder(java, "-version").redirectErrorStream(true).start();
        process.waitFor();
        return process.pid();
    }

    private static final class FakeRuntime implements ServiceRuntime, AdoptableRuntime {

        @Override
        public ServiceHandle start(ServiceLaunch launch) {
            throw new UnsupportedOperationException("im Übernahme-Test wird nichts gestartet");
        }

        @Override
        public ServiceHandle adopt(ServiceLaunch launch, long pid) {
            return new FakeHandle(launch, pid);
        }

        @Override
        public void stop(ServiceHandle handle, Duration timeout) {
        }

        @Override
        public void kill(ServiceHandle handle) {
        }

        @Override
        public boolean isAlive(ServiceHandle handle) {
            return true;
        }

        @Override
        public List<String> logs(ServiceHandle handle) {
            return List.of();
        }
    }

    private record FakeHandle(ServiceLaunch launch, long pid) implements ServiceHandle {

        @Override
        public ServiceId id() {
            return launch.id();
        }

        @Override
        public String nativeId() {
            return Long.toString(pid);
        }

        @Override
        public Path directory() {
            return launch.directory();
        }

        @Override
        public boolean isAlive() {
            return true;
        }
    }
}
