package dev.bytestorm.api.service;

import dev.bytestorm.proto.RuntimeType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Startparameter eines Services, unabhängig von Process- oder Docker-Runtime
 * (Konzept §12.1).
 *
 * @param memoryMb    Heap-Größe in MB ({@code -Xmx})
 * @param jvmArgs     zusätzliche JVM-Argumente, z. B. {@code -XX:+UseG1GC}
 * @param javaArgs    Argumente für die Plattform, z. B. {@code nogui}
 * @param runtime     gewählte Runtime
 * @param environment zusätzliche Umgebungsvariablen
 * @param javaVersion abweichende Java-Version oder {@code null} für die globale
 */
public record LaunchOptions(
        int memoryMb,
        List<String> jvmArgs,
        List<String> javaArgs,
        RuntimeType runtime,
        Map<String, String> environment,
        Integer javaVersion) {

    /**
     * @param memoryMb    Heap-Größe in MB, muss positiv sein
     * @param jvmArgs     JVM-Argumente
     * @param javaArgs    Plattform-Argumente
     * @param runtime     Runtime
     * @param environment Umgebungsvariablen
     * @param javaVersion abweichende Java-Version oder {@code null}
     */
    public LaunchOptions {
        if (memoryMb <= 0) {
            throw new IllegalArgumentException("memoryMb muss positiv sein: " + memoryMb);
        }
        jvmArgs = List.copyOf(jvmArgs);
        javaArgs = List.copyOf(javaArgs);
        runtime = Objects.requireNonNull(runtime, "runtime");
        environment = Map.copyOf(environment);
        if (javaVersion != null && javaVersion <= 0) {
            throw new IllegalArgumentException("javaVersion muss positiv sein: " + javaVersion);
        }
    }

    /**
     * @return ein neuer Builder mit sinnvollen Defaults
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder für {@link LaunchOptions}.
     */
    public static final class Builder {

        private int memoryMb = 1024;
        private final List<String> jvmArgs = new ArrayList<>();
        private final List<String> javaArgs = new ArrayList<>();
        private RuntimeType runtime = RuntimeType.PROCESS;
        private final Map<String, String> environment = new LinkedHashMap<>();
        private Integer javaVersion;

        private Builder() {
        }

        /**
         * @param memoryMb Heap-Größe in MB
         * @return this
         */
        public Builder memoryMb(int memoryMb) {
            this.memoryMb = memoryMb;
            return this;
        }

        /**
         * @param arg einzelnes JVM-Argument
         * @return this
         */
        public Builder jvmArg(String arg) {
            jvmArgs.add(arg);
            return this;
        }

        /**
         * @param args JVM-Argumente
         * @return this
         */
        public Builder jvmArgs(Collection<String> args) {
            jvmArgs.addAll(args);
            return this;
        }

        /**
         * @param arg einzelnes Plattform-Argument
         * @return this
         */
        public Builder javaArg(String arg) {
            javaArgs.add(arg);
            return this;
        }

        /**
         * @param args Plattform-Argumente
         * @return this
         */
        public Builder javaArgs(Collection<String> args) {
            javaArgs.addAll(args);
            return this;
        }

        /**
         * @param runtime Runtime
         * @return this
         */
        public Builder runtime(RuntimeType runtime) {
            this.runtime = runtime;
            return this;
        }

        /**
         * @param key   Name der Variablen
         * @param value Wert
         * @return this
         */
        public Builder environment(String key, String value) {
            environment.put(key, value);
            return this;
        }

        /**
         * @param values Umgebungsvariablen
         * @return this
         */
        public Builder environment(Map<String, String> values) {
            environment.putAll(values);
            return this;
        }

        /**
         * @param javaVersion abweichende Java-Version oder {@code null}
         * @return this
         */
        public Builder javaVersion(Integer javaVersion) {
            this.javaVersion = javaVersion;
            return this;
        }

        /**
         * @return die unveränderlichen Startparameter
         */
        public LaunchOptions build() {
            return new LaunchOptions(memoryMb, jvmArgs, javaArgs, runtime, environment, javaVersion);
        }
    }
}
