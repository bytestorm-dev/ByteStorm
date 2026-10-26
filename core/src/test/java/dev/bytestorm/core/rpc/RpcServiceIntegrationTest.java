package dev.bytestorm.core.rpc;

import dev.bytestorm.codec.ProtobufCodec;
import dev.bytestorm.codec.TypeRegistry;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.core.redis.RedisProvider;
import dev.bytestorm.core.support.TestServers;
import dev.bytestorm.proto.CommandResult;
import dev.bytestorm.proto.ExecuteCommand;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.StartServiceRequest;
import dev.bytestorm.proto.StartServiceResponse;
import dev.bytestorm.proto.StopServiceRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.remote.RemoteServiceTimeoutException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RpcServiceIntegrationTest {

    private RedisProvider provider;
    private RpcService rpc;
    private final AtomicInteger noopCalls = new AtomicInteger();

    @BeforeAll
    void connect() {
        TestServers.assumeRedis();
        provider = RedisProvider.connect(new RedisSettings(TestServers.redisAddress(), null),
                new ProtobufCodec(TypeRegistry.byteStormDefaults()));
        rpc = new RpcService(provider.client());

        rpc.register(TestRpc.class, new TestRpc() {
            @Override
            public String echo(String value) {
                return "echo:" + value;
            }

            @Override
            public void noop() {
                noopCalls.incrementAndGet();
            }
        }, 4);

        rpc.register(SlowRpc.class, () -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 1);

        rpc.register(CloudRpc.class, new CloudRpc() {
            @Override
            public StartServiceResponse startService(StartServiceRequest request) {
                return StartServiceResponse.newBuilder()
                        .setSuccess(true)
                        .setService(ServiceInfo.newBuilder()
                                .setName(request.getGroup() + "-1")
                                .setGroup(request.getGroup())
                                .putProperties("token", request.getPropertiesOrDefault("token", "")))
                        .build();
            }

            @Override
            public void stopService(StopServiceRequest request) {
                // bewusst leer
            }

            @Override
            public CommandResult executeCommand(ExecuteCommand request) {
                return CommandResult.newBuilder()
                        .setSuccess(true)
                        .setOutput("executed:" + request.getCommand())
                        .build();
            }
        }, 2);
    }

    @AfterAll
    void cleanup() {
        if (rpc != null) {
            rpc.deregister(TestRpc.class);
            rpc.deregister(SlowRpc.class);
            rpc.deregister(CloudRpc.class);
        }
        if (provider != null) {
            provider.client().getKeys().deleteByPattern("bs:*");
            provider.close();
        }
    }

    @Test
    void stringRoundtripWorks() {
        TestRpc client = rpc.client(TestRpc.class, Duration.ofSeconds(5));

        assertEquals("echo:hallo", client.echo("hallo"));
    }

    @Test
    void voidMethodWorks() {
        TestRpc client = rpc.client(TestRpc.class, Duration.ofSeconds(5));
        int before = noopCalls.get();

        client.noop();

        assertEquals(before + 1, noopCalls.get());
    }

    @Test
    void timeoutEndsTheCall() {
        SlowRpc client = rpc.client(SlowRpc.class, Duration.ofMillis(300));

        assertThrows(RemoteServiceTimeoutException.class, client::slow,
                "ohne Timeout würde der Aufruf am Worker hängen bleiben");
    }

    @Test
    void cloudRpcRoundtripsProtoMessages() {
        CloudRpc client = rpc.client(CloudRpc.class, Duration.ofSeconds(5));

        StartServiceResponse response = client.startService(StartServiceRequest.newBuilder()
                .setGroup("Lobby")
                .putProperties("token", "geheim")
                .build());

        assertTrue(response.getSuccess());
        assertEquals("Lobby-1", response.getService().getName());
        assertEquals("geheim", response.getService().getPropertiesOrThrow("token"));

        client.stopService(StopServiceRequest.newBuilder().setService("id").setForce(true).build());

        CommandResult result = client.executeCommand(ExecuteCommand.newBuilder()
                .setService("id")
                .setCommand("say hi")
                .build());
        assertEquals("executed:say hi", result.getOutput());
    }
}
