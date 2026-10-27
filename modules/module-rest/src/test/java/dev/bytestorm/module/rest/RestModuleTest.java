package dev.bytestorm.module.rest;

import dev.bytestorm.api.ByteStormApi;
import dev.bytestorm.api.CloudModule;
import dev.bytestorm.api.module.ModuleContext;
import dev.bytestorm.api.service.Services;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceType;
import dev.bytestorm.proto.StartServiceResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestModuleTest {

    private static final String KEY = "geheim-geheim-1234";

    private static final String LOBBY_ID = "1e1f0dd4-0000-4000-8000-000000000001";

    private static final String PROXY_ID = "1e1f0dd4-0000-4000-8000-000000000002";

    @TempDir
    Path temp;

    private final FakeServices services = new FakeServices();

    @Test
    void servicesAnswerWithAValidKeyAnd401Without() throws Exception {
        int port = freePort();
        RestModule module = module(Map.of("host", "127.0.0.1", "port", String.valueOf(port), "apiKey", KEY));
        module.onEnable();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertEquals(401, get(client, port, null).statusCode(), "ohne Key keine Antwort");
            assertEquals(401, get(client, port, "falscher-key-1234").statusCode(), "falscher Key wird abgewiesen");

            HttpResponse<String> response = get(client, port, KEY);
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("content-type").orElse("").contains("application/json"),
                    "Antwort ist JSON");
            assertTrue(response.body().contains("\"services\""), response.body());
            assertTrue(response.body().contains("\"Lobby-1\""), response.body());
            assertTrue(response.body().contains("\"state\":\"ONLINE\""), response.body());
            assertTrue(response.body().contains("\"port\":30001"), response.body());
        } finally {
            module.onDisable();
        }
    }

    @Test
    void theKeyIsAlsoAcceptedInTheApiKeyHeader() throws Exception {
        int port = freePort();
        RestModule module = module(Map.of("host", "127.0.0.1", "port", String.valueOf(port), "apiKey", KEY));
        module.onEnable();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(uri(port, "/services"))
                    .header(RestModule.API_KEY_HEADER, KEY)
                    .GET()
                    .build();
            assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            module.onDisable();
        }
    }

    @Test
    void withoutAKeyTheModuleRefusesToLoad() {
        RestModule module = new RestModule();
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> module.onLoad(context(Map.of("port", "8080"), api())));
        assertTrue(missing.getMessage().contains("apiKey"), missing.getMessage());

        IllegalArgumentException shortKey = assertThrows(IllegalArgumentException.class,
                () -> module.onLoad(context(Map.of("apiKey", "kurz"), api())));
        assertTrue(shortKey.getMessage().contains("apiKey"), shortKey.getMessage());

        IllegalArgumentException badPort = assertThrows(IllegalArgumentException.class,
                () -> module.onLoad(context(Map.of("apiKey", KEY, "port", "70000"), api())));
        assertTrue(badPort.getMessage().contains("port"), badPort.getMessage());
    }

    @Test
    void unknownPathsAndIdempotentLifecycle() throws Exception {
        int port = freePort();
        RestModule module = module(Map.of("host", "127.0.0.1", "port", String.valueOf(port), "apiKey", KEY));
        module.onEnable();
        module.onEnable();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertTrue(module.isRunning());
            HttpResponse<String> missing = get(client, port, KEY, "/unbekannt");
            assertEquals(404, missing.statusCode());
        } finally {
            module.onDisable();
            module.onDisable();
        }
        assertFalse(module.isRunning());
    }

    @Test
    void theModuleIsFoundByTheServiceLoader() {
        List<CloudModule> modules = ServiceLoader.load(CloudModule.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();

        assertEquals(List.of(RestModule.NAME), modules.stream().map(CloudModule::name).toList());
    }

    @Test
    void startPassesGroupAndPropertiesAndAnswersWithTheService() throws Exception {
        int port = freePort();
        RestModule module = module(Map.of("host", "127.0.0.1", "port", String.valueOf(port), "apiKey", KEY));
        module.onEnable();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = post(client, port, KEY, RestModule.START_PATH,
                    "{\"group\":\"Lobby\",\"properties\":{\"template\":\"lobby\",\"slots\":3}}");

            assertEquals(201, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"service\""), response.body());
            assertTrue(response.body().contains("\"name\":\"Lobby-1\""), response.body());
            assertEquals(List.of("start:Lobby"), services.calls);
            assertEquals(Map.of("template", "lobby", "slots", "3"), services.lastProperties,
                    "Properties kommen als Text an und gelten für diesen Start");
        } finally {
            module.onDisable();
        }
    }

    @Test
    void startWithoutPropertiesSendsAnEmptyMap() throws Exception {
        int port = freePort();
        RestModule module = module(Map.of("host", "127.0.0.1", "port", String.valueOf(port), "apiKey", KEY));
        module.onEnable();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = post(client, port, KEY, RestModule.START_PATH, "{\"group\":\"Lobby\"}");

            assertEquals(201, response.statusCode(), response.body());
            assertEquals(Map.of(), services.lastProperties);
        } finally {
            module.onDisable();
        }
    }

    @Test
    void aFailedStartAnswers409WithTheCloudMessage() throws Exception {
        int port = freePort();
        RestModule module = module(Map.of("host", "127.0.0.1", "port", String.valueOf(port), "apiKey", KEY));
        module.onEnable();
        try (HttpClient client = HttpClient.newHttpClient()) {
            services.startError = "Gruppe 'Lobby' ist im Wartungsmodus";
            HttpResponse<String> rejected = post(client, port, KEY, RestModule.START_PATH, "{\"group\":\"Lobby\"}");
            assertEquals(409, rejected.statusCode(), rejected.body());
            assertTrue(rejected.body().contains("Wartungsmodus"), rejected.body());
            assertTrue(rejected.body().contains("\"error\""), rejected.body());

            services.startError = null;
            services.failure = new IllegalStateException("Provisioner nicht erreichbar");
            HttpResponse<String> broken = post(client, port, KEY, RestModule.START_PATH, "{\"group\":\"Lobby\"}");
            assertEquals(409, broken.statusCode(), broken.body());
            assertTrue(broken.body().contains("Provisioner nicht erreichbar"), broken.body());
        } finally {
            module.onDisable();
        }
    }

    @Test
    void stopAndCommandAreForwardedWithTheServiceId() throws Exception {
        int port = freePort();
        RestModule module = module(Map.of("host", "127.0.0.1", "port", String.valueOf(port), "apiKey", KEY));
        module.onEnable();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> stopped = post(client, port, KEY, RestModule.STOP_PATH,
                    "{\"id\":\"" + LOBBY_ID + "\"}");
            assertEquals(200, stopped.statusCode(), stopped.body());
            assertTrue(stopped.body().contains("\"stopped\":true"), stopped.body());

            HttpResponse<String> killed = post(client, port, KEY, RestModule.STOP_PATH,
                    "{\"id\":\"" + LOBBY_ID + "\",\"force\":true}");
            assertEquals(200, killed.statusCode(), killed.body());

            HttpResponse<String> sent = post(client, port, KEY, RestModule.COMMAND_PATH,
                    "{\"id\":\"" + PROXY_ID + "\",\"command\":\"say hallo welt\"}");
            assertEquals(200, sent.statusCode(), sent.body());
            assertTrue(sent.body().contains("\"sent\":\"say hallo welt\""), sent.body());

            assertEquals(List.of("stop:" + LOBBY_ID + ":false", "stop:" + LOBBY_ID + ":true",
                    "command:" + PROXY_ID + ":say hallo welt"), services.calls);
        } finally {
            module.onDisable();
        }
    }

    @Test
    void aFailingStopAnswers409() throws Exception {
        int port = freePort();
        RestModule module = module(Map.of("host", "127.0.0.1", "port", String.valueOf(port), "apiKey", KEY));
        module.onEnable();
        try (HttpClient client = HttpClient.newHttpClient()) {
            services.failure = new IllegalStateException("Unbekannter Service: " + LOBBY_ID);
            HttpResponse<String> response = post(client, port, KEY, RestModule.STOP_PATH,
                    "{\"id\":\"" + LOBBY_ID + "\"}");

            assertEquals(409, response.statusCode(), response.body());
            assertTrue(response.body().contains("Unbekannter Service"), response.body());
        } finally {
            module.onDisable();
        }
    }

    @Test
    void brokenRequestsAnswer400() throws Exception {
        int port = freePort();
        RestModule module = module(Map.of("host", "127.0.0.1", "port", String.valueOf(port), "apiKey", KEY));
        module.onEnable();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertEquals(400, post(client, port, KEY, RestModule.START_PATH, "").statusCode(), "Body fehlt");
            assertEquals(400, post(client, port, KEY, RestModule.START_PATH, "kein json").statusCode());
            assertEquals(400, post(client, port, KEY, RestModule.START_PATH, "[]").statusCode(), "kein Objekt");
            assertEquals(400, post(client, port, KEY, RestModule.START_PATH, "{}").statusCode(), "group fehlt");
            assertEquals(400, post(client, port, KEY, RestModule.START_PATH,
                    "{\"group\":\"Lobby\",\"properties\":[1,2]}").statusCode(), "properties kein Objekt");
            assertEquals(400, post(client, port, KEY, RestModule.STOP_PATH, "{}").statusCode(), "id fehlt");
            assertEquals(400, post(client, port, KEY, RestModule.STOP_PATH,
                    "{\"id\":\"keine-uuid\"}").statusCode(), "id ist keine UUID");
            assertEquals(400, post(client, port, KEY, RestModule.COMMAND_PATH,
                    "{\"id\":\"" + LOBBY_ID + "\"}").statusCode(), "command fehlt");
            assertEquals(400, post(client, port, KEY, RestModule.COMMAND_PATH,
                    "{\"id\":\"" + LOBBY_ID + "\",\"command\":\"  \"}").statusCode(), "command leer");

            assertTrue(services.calls.isEmpty(), "kein fehlerhafter Aufruf erreicht die Cloud: " + services.calls);
        } finally {
            module.onDisable();
        }
    }

    @Test
    void theNewRoutesNeedAKeyToo() throws Exception {
        int port = freePort();
        RestModule module = module(Map.of("host", "127.0.0.1", "port", String.valueOf(port), "apiKey", KEY));
        module.onEnable();
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String path : List.of(RestModule.START_PATH, RestModule.STOP_PATH, RestModule.COMMAND_PATH)) {
                HttpResponse<String> response = post(client, port, null, path, "{\"group\":\"Lobby\"}");
                assertEquals(401, response.statusCode(), path);
                assertTrue(response.body().contains("API-Key"), response.body());
            }
            assertTrue(services.calls.isEmpty());
        } finally {
            module.onDisable();
        }
    }

    private RestModule module(Map<String, String> settings) {
        RestModule module = new RestModule();
        module.onLoad(context(settings, api()));
        return module;
    }

    private ModuleContext context(Map<String, String> settings, ByteStormApi api) {
        Logger logger = LoggerFactory.getLogger("bytestorm.module.rest.test");
        Path directory = temp.resolve(RestModule.NAME);
        return new ModuleContext() {
            @Override
            public ByteStormApi api() {
                return api;
            }

            @Override
            public Path dataDirectory() {
                return directory;
            }

            @Override
            public Logger logger() {
                return logger;
            }

            @Override
            public Map<String, String> settings() {
                return settings;
            }
        };
    }

    private ByteStormApi api() {
        return new ByteStormApi() {
            @Override
            public Services services() {
                return services;
            }

            @Override
            public dev.bytestorm.api.player.Players players() {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.bytestorm.api.group.Groups groups() {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.bytestorm.api.event.Events events() {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.bytestorm.api.template.Templates templates() {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.bytestorm.api.module.Modules modules() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static ServiceInfo proxy() {
        return ServiceInfo.newBuilder()
                .setId(PROXY_ID)
                .setName("Proxy-1")
                .setGroup("Proxy")
                .setType(ServiceType.PROXY)
                .setState(ServiceState.STARTING)
                .build();
    }

    private static ServiceInfo lobby(Map<String, String> properties) {
        return ServiceInfo.newBuilder()
                .setId(LOBBY_ID)
                .setName("Lobby-1")
                .setGroup("Lobby")
                .setType(ServiceType.SERVER)
                .setState(ServiceState.ONLINE)
                .setHost("127.0.0.1")
                .setPort(30001)
                .setOnlinePlayers(3)
                .setMaxPlayers(100)
                .putAllProperties(properties)
                .build();
    }

    /** Services-Fassade, die Aufrufe mitschreibt und Fehler auf Wunsch liefert. */
    private static final class FakeServices implements Services {

        private final List<String> calls = new ArrayList<>();
        private Map<String, String> lastProperties = Map.of();
        /** Fachlicher Fehlertext im Start-Ergebnis. */
        private String startError;
        /** Fehler, mit dem Stop und Command scheitern. */
        private RuntimeException failure;

        @Override
        public Collection<ServiceInfo> all() {
            return List.of(proxy(), lobby(Map.of()));
        }

        @Override
        public List<ServiceInfo> byGroup(String group) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ServiceInfo> byName(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ServiceInfo> byId(String serviceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<StartServiceResponse> start(String group, Map<String, String> properties) {
            calls.add("start:" + group);
            lastProperties = properties;
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            if (startError != null) {
                return CompletableFuture.completedFuture(StartServiceResponse.newBuilder()
                        .setSuccess(false)
                        .setError(startError)
                        .build());
            }
            return CompletableFuture.completedFuture(StartServiceResponse.newBuilder()
                    .setSuccess(true)
                    .setService(lobby(properties))
                    .build());
        }

        @Override
        public CompletableFuture<Void> stop(String serviceId, boolean force) {
            calls.add("stop:" + serviceId + ":" + force);
            return failure == null ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(failure);
        }

        @Override
        public CompletableFuture<Void> restart(String serviceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> execute(String serviceId, String command) {
            calls.add("command:" + serviceId + ":" + command);
            return failure == null ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(failure);
        }
    }

    private static HttpResponse<String> get(HttpClient client, int port, String key) throws Exception {
        return get(client, port, key, RestModule.SERVICES_PATH);
    }

    private static HttpResponse<String> get(HttpClient client, int port, String key, String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(port, path));
        if (key != null) {
            builder.header("Authorization", "Bearer " + key);
        }
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> post(HttpClient client, int port, String key, String path, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(port, path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (key != null) {
            builder.header("Authorization", "Bearer " + key);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    /** Der Server darf keinen festen Testport belegen; ein freier reicht. */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
