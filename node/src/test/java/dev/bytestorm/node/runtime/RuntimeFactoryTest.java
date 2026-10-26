package dev.bytestorm.node.runtime;

import dev.bytestorm.api.service.ServiceHandle;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceGroup;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeFactoryTest {

    private final ServiceRuntime process = new FakeRuntime(RuntimeType.PROCESS);
    private final ServiceRuntime docker = new FakeRuntime(RuntimeType.DOCKER);

    @Test
    void usesTheGlobalRuntimeWithoutAGroupOverride() {
        RuntimeFactory factory = RuntimeFactory.of(process, docker);

        assertSame(process, factory.forGroup(RuntimeType.PROCESS, ServiceGroup.getDefaultInstance()));
    }

    @Test
    void aGroupOverrideWinsOverTheGlobalRuntime() {
        RuntimeFactory factory = RuntimeFactory.of(process, docker);
        ServiceGroup group = ServiceGroup.newBuilder().setName("Lobby").setRuntime(RuntimeType.DOCKER).build();

        assertSame(docker, factory.forGroup(RuntimeType.PROCESS, group));
    }

    @Test
    void failsClearlyForARuntimeThatWasNotConfigured() {
        RuntimeFactory factory = RuntimeFactory.of(process, null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> factory.forType(RuntimeType.DOCKER));

        assertTrue(error.getMessage().contains("DOCKER"), error.getMessage());
        assertTrue(error.getMessage().contains("PROCESS"), error.getMessage());
    }

    @Test
    void theFactoryDoesNotObserveLaterChangesToTheSourceMap() {
        Map<RuntimeType, ServiceRuntime> source = new java.util.EnumMap<>(RuntimeType.class);
        source.put(RuntimeType.PROCESS, process);
        RuntimeFactory factory = new RuntimeFactory(source);

        source.clear();

        assertSame(process, factory.forType(RuntimeType.PROCESS));
    }

    @Test
    void rejectsAnUnrecognizedRuntimeType() {
        RuntimeFactory factory = RuntimeFactory.of(process, docker);

        assertThrows(IllegalStateException.class, () -> factory.forType(RuntimeType.UNRECOGNIZED));
    }

    @Test
    void processIsRequired() {
        assertThrows(NullPointerException.class, () -> RuntimeFactory.of(null, docker));
    }

    private record FakeHandle(ServiceId id, Path directory) implements ServiceHandle {

        @Override
        public String nativeId() {
            return "fake";
        }

        @Override
        public boolean isAlive() {
            return true;
        }
    }

    private record FakeRuntime(RuntimeType type) implements ServiceRuntime {

        @Override
        public ServiceHandle start(ServiceLaunch launch) {
            return new FakeHandle(launch.id(), launch.directory());
        }

        @Override
        public void stop(ServiceHandle handle, Duration timeout) {
            // nichts zu tun
        }

        @Override
        public void kill(ServiceHandle handle) {
            // nichts zu tun
        }

        @Override
        public boolean isAlive(ServiceHandle handle) {
            return true;
        }

        @Override
        public List<String> logs(ServiceHandle handle) {
            return List.of();
        }

        @Override
        public String toString() {
            return type.name();
        }
    }
}
