package dev.bytestorm.node.runtime;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;

import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * docker-java-Implementierung von {@link DockerEngine} (Konzept §12.3).
 *
 * <p>Der Service-Ordner wird auf {@link DockerRuntime#DATA_DIRECTORY} gebunden,
 * der Port eins zu eins veröffentlicht. Ein vorhandenes Image wird genutzt,
 * kein Image gebaut.
 */
final class DockerJavaEngine implements DockerEngine {

    private final DockerClient client;

    /**
     * @param dockerHost Docker-Host, z. B. {@code unix:///var/run/docker.sock};
     *                   {@code null}/leer nutzt die docker-java-Defaults
     */
    DockerJavaEngine(String dockerHost) {
        DefaultDockerClientConfig.Builder config = DefaultDockerClientConfig.createDefaultConfigBuilder();
        if (dockerHost != null && !dockerHost.isBlank()) {
            config.withDockerHost(dockerHost.trim());
        }
        this.client = DockerClientBuilder.getInstance(config.build()).build();
    }

    @Override
    public String start(DockerContainerRequest request) {
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withBinds(new Bind(request.dataDirectory().toString(),
                        new Volume(DockerRuntime.DATA_DIRECTORY)))
                .withPortBindings(new PortBinding(Ports.Binding.bindPort(request.port()),
                        ExposedPort.tcp(request.port())))
                // §12.3: gestoppte Container werden wie bei --rm entfernt.
                .withAutoRemove(true);

        CreateContainerResponse container = client.createContainerCmd(request.image())
                .withHostConfig(hostConfig)
                .withWorkingDir(request.workingDirectory())
                .withCmd(request.command())
                .withEnv(environment(request.environment()))
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();
        client.startContainerCmd(container.getId()).exec();
        return container.getId();
    }

    @Override
    public void stop(String containerId, Duration timeout) {
        client.stopContainerCmd(containerId)
                .withTimeout((int) Math.max(1, timeout.toSeconds()))
                .exec();
    }

    @Override
    public void kill(String containerId) {
        client.killContainerCmd(containerId).exec();
    }

    @Override
    public boolean isRunning(String containerId) {
        Boolean running = client.inspectContainerCmd(containerId).exec().getState().getRunning();
        return Boolean.TRUE.equals(running);
    }

    @Override
    public Closeable streamLogs(String containerId, DockerLogHandler handler) {
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            private boolean ended;

            @Override
            public void onNext(Frame frame) {
                if (frame.getStreamType() == StreamType.STDERR) {
                    handler.frame(true, frame.getPayload());
                } else {
                    handler.frame(false, frame.getPayload());
                }
            }

            @Override
            public void onComplete() {
                end();
                super.onComplete();
            }

            @Override
            public void onError(Throwable throwable) {
                end();
            }

            private void end() {
                if (!ended) {
                    ended = true;
                    handler.end();
                }
            }
        };
        client.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .withFollowStream(true)
                .withTailAll()
                .exec(callback);
        return callback;
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception e) {
            // Beim Herunterfahren nicht mehr relevant.
        }
    }

    private static List<String> environment(Map<String, String> environment) {
        return environment.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();
    }
}
