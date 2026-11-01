package dev.bytestorm.module.rest;

import dev.bytestorm.api.ByteStormApi;
import dev.bytestorm.api.CloudModule;
import dev.bytestorm.api.group.Groups;
import dev.bytestorm.api.module.ModuleContext;
import dev.bytestorm.api.player.Players;
import dev.bytestorm.api.service.CrashBudget;
import dev.bytestorm.api.service.ServiceCrash;
import dev.bytestorm.api.service.Services;
import dev.bytestorm.proto.PlayerInfo;
import dev.bytestorm.proto.ServiceGroup;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestModuleTest {

    private static final String KEY = "geheim-geheim-1234";

    private static final String LOBBY_ID = "1e1f0dd4-0000-4000-8000-000000000001";

    private static final String PROXY_ID = "1e1f0dd4-0000-4000-8000-000000000002";

    private static final String PLAYER_ID = "1e1f0dd4-0000-4000-8000-000000000003";

    @TempDir
    Path temp;

    private final FakeServices services = new FakeServices();

    private final FakeGroups groups = new FakeGroups();

    private final FakePlayers players = new FakePlayers();

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
            for (String path : List.of(RestModule.START_PATH, RestModule.STOP_PATH,
                    RestModule.COMMAND_PATH, RestModule.RESTART_PATH, RestModule.MAINTENANCE_PATH,
                    RestModule.SCALE_PATH, RestModule.CONNECT_PATH, RestModule.KICK_PATH,
                    RestModule.REAPPLY_PATH, RestModule.ONLINE_PATH)) {
                HttpResponse<String> response = post(client, port, null, path, "{\"group\":\"Lobby\"}");
                assertEquals(401, response.statusCode(), path);
                assertTrue(response.body().contains("API-Key"), response.body());
            }
            assertEquals(401, get(client, port, null, RestModule.GROUPS_PATH).statusCode());
            assertEquals(401, get(client, port, null, RestModule.PLAYERS_PATH).statusCode());
            assertTrue(services.calls.isEmpty());
            assertTrue(groups.calls.isEmpty());
            assertTrue(players.calls.isEmpty());
        } finally {
            module.onDisable();
        }
    }

    @Test
    void restartGroupsAndPlayersReachTheCloud() throws Exception {
        int port = freePort();
        RestModule module = module(Map.of("host", "127.0.0.1", "port", String.valueOf(port), "apiKey", KEY));
        module.onEnable();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> restarted = post(client, port, KEY, RestModule.RESTART_PATH,
                    "{\"id\":\"" + LOBBY_ID + "\"}");
            assertEquals(200, restarted.statusCode(), restarted.body());
            assertTrue(restarted.body().contains("\"restarted\":true"), restarted.body());

            HttpResponse<String> definitions = get(client, port, KEY, RestModule.GROUPS_PATH);
            assertEquals(200, definitions.statusCode(), definitions.body());
            assertTrue(definitions.body().contains("\"groups\""), definitions.body());
            assertTrue(definitions.body().contains("\"Lobby\""), definitions.body());
            assertTrue(definitions.body().contains("\"services\":1"),
                    "Lobby-1 zählt zu Lobby: " + definitions.body());
            assertTrue(definitions.body().contains("\"maintenance\":true"),
                    "Proxy steht in Wartung: " + definitions.body());

            HttpResponse<String> maintenance = post(client, port, KEY, RestModule.MAINTENANCE_PATH,
                    "{\"name\":\"Lobby\",\"enabled\":true}");
            assertEquals(200, maintenance.statusCode(), maintenance.body());
            assertTrue(maintenance.body().contains("\"maintenance\":true"), maintenance.body());

            HttpResponse<String> scaled = post(client, port, KEY, RestModule.SCALE_PATH,
                    "{\"name\":\"Lobby\",\"target\":3}");
            assertEquals(200, scaled.statusCode(), scaled.body());
            assertTrue(scaled.body().contains("\"target\":3"), scaled.body());

            HttpResponse<String> online = get(client, port, KEY, RestModule.PLAYERS_PATH);
            assertEquals(200, online.statusCode(), online.body());
            assertTrue(online.body().contains("\"Notch\""), online.body());
            assertTrue(online.body().contains("\"server\":\"Lobby-1\""), online.body());

            HttpResponse<String> connected = post(client, port, KEY, RestModule.CONNECT_PATH,
                    "{\"uuid\":\"" + PLAYER_ID + "\",\"service\":\"Lobby-1\"}");
            assertEquals(200, connected.statusCode(), connected.body());

            HttpResponse<String> kicked = post(client, port, KEY, RestModule.KICK_PATH,
                    "{\"uuid\":\"" + PLAYER_ID + "\",\"reason\":\"Bitte Regeln lesen\"}");
            assertEquals(200, kicked.statusCode(), kicked.body());
            assertTrue(kicked.body().contains("\"kicked\":true"), kicked.body());

            assertEquals(List.of("restart:" + LOBBY_ID), services.calls);
            assertEquals(List.of("maintenance:Lobby:true", "scale:Lobby:3"), groups.calls);
            assertEquals(List.of("connect:" + PLAYER_ID + ":Lobby-1",
                    "kick:" + PLAYER_ID + ":Bitte Regeln lesen"), players.calls);
        } finally {
            module.onDisable();
        }
    }

    @Test
    void theNewRoutesRejectBrokenBodies() throws Exception {
        int port = freePort();
        RestModule module = module(Map.of("host", "127.0.0.1", "port", String.valueOf(port), "apiKey", KEY));
        module.onEnable();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertEquals(400, post(client, port, KEY, RestModule.RESTART_PATH, "{}").statusCode(),
                    "id fehlt");
            assertEquals(400, post(client, port, KEY, RestModule.MAINTENANCE_PATH,
                    "{\"name\":\"Lobby\"}").statusCode(), "enabled fehlt");
            assertEquals(400, post(client, port, KEY, RestModule.MAINTENANCE_PATH,
                    "{\"name\":\"Lobby\",\"enabled\":\"ja\"}").statusCode(), "enabled ist kein Schalter");
            assertEquals(400, post(client, port, KEY, RestModule.SCALE_PATH,
                    "{\"name\":\"Lobby\",\"target\":-1}").statusCode(), "negative Anzahl");
            assertEquals(400, post(client, port, KEY, RestModule.SCALE_PATH,
                    "{\"name\":\"Lobby\",\"target\":\"viele\"}").statusCode(), "keine Zahl");
            assertEquals(400, post(client, port, KEY, RestModule.CONNECT_PATH,
                    "{\"uuid\":\"keine-uuid\",\"service\":\"Lobby-1\"}").statusCode());
            assertEquals(400, post(client, port, KEY, RestModule.CONNECT_PATH,
                    "{\"uuid\":\"" + PLAYER_ID + "\"}").statusCode(), "service fehlt");
            assertEquals(400, post(client, port, KEY, RestModule.KICK_PATH,
                    "{}").statusCode(), "uuid fehlt");

            assertTrue(services.calls.isEmpty(), "kein fehlerhafter Aufruf erreicht die Cloud: " + services.calls);
            assertTrue(groups.calls.isEmpty(), groups.calls.toString());
            assertTrue(players.calls.isEmpty(), players.calls.toString());
        } finally {
            module.onDisable();
        }
    }

    @Test
    void aFailingRestartOrKickAnswers409() throws Exception {
        int port = freePort();
        RestModule module = module(Map.of("host", "127.0.0.1", "port", String.valueOf(port), "apiKey", KEY));
        module.onEnable();
        try (HttpClient client = HttpClient.newHttpClient()) {
            services.failure = new IllegalStateException("Service läuft nicht");
            HttpResponse<String> restart = post(client, port, KEY, RestModule.RESTART_PATH,
                    "{\"id\":\"" + LOBBY_ID + "\"}");
            assertEquals(409, restart.statusCode(), restart.body());
            assertTrue(restart.body().contains("Service läuft nicht"), restart.body());

            players.failure = new IllegalStateException("Spieler ist nicht verbunden");
            HttpResponse<String> kick = post(client, port, KEY, RestModule.KICK_PATH,
                    "{\"uuid\":\"" + PLAYER_ID + "\"}");
            assertEquals(409, kick.statusCode(), kick.body());
            assertTrue(kick.body().contains("nicht verbunden"), kick.body());
            assertEquals(List.of("kick:" + PLAYER_ID + ":"), players.calls,
                    "ohne Begründung geht ein leerer Text an die Cloud");
        } finally {
            module.onDisable();
        }
    }

    @Test
    void reapplyAndOnlineReachTheCloud() throws Exception {
        int port = freePort();
        RestModule module = module(Map.of("host", "127.0.0.1", "port", String.valueOf(port), "apiKey", KEY));
        module.onEnable();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> reapplied = post(client, port, KEY, RestModule.REAPPLY_PATH,
                    "{\"id\":\"" + LOBBY_ID + "\"}");
            assertEquals(200, reapplied.statusCode(), reapplied.body());
            assertTrue(reapplied.body().contains("\"reapplied\":true"), reapplied.body());

            HttpResponse<String> online = post(client, port, KEY, RestModule.ONLINE_PATH,
                    "{\"id\":\"" + PROXY_ID + "\"}");
            assertEquals(200, online.statusCode(), online.body());
            assertTrue(online.body().contains("\"online\":true"), online.body());

            assertEquals(400, post(client, port, KEY, RestModule.REAPPLY_PATH, "{}").statusCode(),
                    "id fehlt");
            assertEquals(400, post(client, port, KEY, RestModule.ONLINE_PATH,
                    "{\"id\":\"keine-uuid\"}").statusCode());

            assertEquals(List.of("reapply:" + LOBBY_ID, "online:" + PROXY_ID), services.calls);
        } finally {
            module.onDisable();
        }
    }

    @Test
    void logsAndCrashDataAreServed() throws Exception {
        int port = freePort();
        RestModule module = module(Map.of("host", "127.0.0.1", "port", String.valueOf(port), "apiKey", KEY));
        module.onEnable();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> overview = get(client, port, KEY);
            assertTrue(overview.body().contains("\"crashBudgetRemaining\":2"), overview.body());
            assertTrue(overview.body().contains("\"crashBudgetTotal\":3"), overview.body());
            assertTrue(overview.body().contains("\"lastCrashReason\":\"OutOfMemoryError\""),
                    overview.body());
            assertTrue(overview.body().contains("\"crashReport\":\"/reports/lobby-1\""), overview.body());

            assertEquals(401, get(client, port, null, RestModule.LOGS_PATH + "?id=" + LOBBY_ID)
                    .statusCode(), "Logs brauchen den Key");

            HttpResponse<String> tail = get(client, port, KEY,
                    RestModule.LOGS_PATH + "?id=" + LOBBY_ID + "&lines=2");
            assertEquals(200, tail.statusCode(), tail.body());
            assertTrue(tail.body().contains("[WARN] Wenig Speicher"), tail.body());
            assertFalse(tail.body().contains("[INFO] Start"),
                    "nur die letzten beiden Zeilen: " + tail.body());

            HttpResponse<String> all = get(client, port, KEY, RestModule.LOGS_PATH + "?id=" + LOBBY_ID);
            assertEquals(200, all.statusCode(), all.body());
            assertTrue(all.body().contains("[INFO] Start"), all.body());

            assertEquals(400, get(client, port, KEY, RestModule.LOGS_PATH).statusCode(), "id fehlt");
            assertEquals(400, get(client, port, KEY, RestModule.LOGS_PATH + "?id=keine-uuid").statusCode());
            assertEquals(400, get(client, port, KEY,
                    RestModule.LOGS_PATH + "?id=" + LOBBY_ID + "&lines=viele").statusCode());

            assertEquals(List.of("logs:" + LOBBY_ID, "logs:" + LOBBY_ID), services.calls);
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
            public Players players() {
                return players;
            }

            @Override
            public Groups groups() {
                return groups;
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
            calls.add("restart:" + serviceId);
            return failure == null ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(failure);
        }

        @Override
        public CompletableFuture<Void> execute(String serviceId, String command) {
            calls.add("command:" + serviceId + ":" + command);
            return failure == null ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(failure);
        }

        @Override
        public CompletableFuture<Void> reapply(String serviceId) {
            calls.add("reapply:" + serviceId);
            return failure == null ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(failure);
        }

        @Override
        public CompletableFuture<Void> online(String serviceId) {
            calls.add("online:" + serviceId);
            return failure == null ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(failure);
        }

        @Override
        public List<String> logs(String serviceId) {
            calls.add("logs:" + serviceId);
            if (failure != null) {
                throw failure;
            }
            return List.of("[INFO] Start", "[INFO] Fertig", "[WARN] Wenig Speicher");
        }

        @Override
        public List<ServiceCrash> lastCrashes() {
            return List.of(new ServiceCrash("Lobby-1", "OutOfMemoryError", 1, 1_700_000_000_000L,
                    "/reports/lobby-1"));
        }

        @Override
        public Map<String, CrashBudget> crashBudgets() {
            return Map.of("Lobby-1", new CrashBudget(2, 3));
        }
    }

    /** Gruppen-Fassade, die Aufrufe mitschreibt und Fehler auf Wunsch liefert. */
    private static final class FakeGroups implements Groups {

        private final List<String> calls = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public Collection<ServiceGroup> all() {
            return List.of(group("Lobby", false), group("Proxy", true));
        }

        @Override
        public Optional<ServiceGroup> get(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> create(ServiceGroup group) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> update(ServiceGroup group) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> delete(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> setMaintenance(String name, boolean maintenance) {
            calls.add("maintenance:" + name + ":" + maintenance);
            return done(failure);
        }

        @Override
        public CompletableFuture<Void> scale(String name, int targetServices) {
            calls.add("scale:" + name + ":" + targetServices);
            return done(failure);
        }
    }

    /** Spieler-Fassade mit einem verbundenen Spieler. */
    private static final class FakePlayers implements Players {

        private final List<String> calls = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public Collection<PlayerInfo> online() {
            return List.of(PlayerInfo.newBuilder()
                    .setUuid(PLAYER_ID)
                    .setName("Notch")
                    .setProxy("Proxy-1")
                    .setServer("Lobby-1")
                    .build());
        }

        @Override
        public Collection<PlayerInfo> all() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PlayerInfo> byUuid(UUID uuid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PlayerInfo> byName(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> connect(UUID uuid, String service) {
            calls.add("connect:" + uuid + ":" + service);
            return done(failure);
        }

        @Override
        public CompletableFuture<Void> kick(UUID uuid, String reason) {
            calls.add("kick:" + uuid + ":" + reason);
            return done(failure);
        }

        @Override
        public CompletableFuture<Duration> playtime(UUID uuid) {
            throw new UnsupportedOperationException();
        }
    }

    private static ServiceGroup group(String name, boolean maintenance) {
        return ServiceGroup.newBuilder()
                .setName(name)
                .setType(ServiceType.SERVER)
                .setMaintenance(maintenance)
                .setMaxServices(4)
                .setMaxPlayers(100)
                .build();
    }

    private static CompletableFuture<Void> done(RuntimeException failure) {
        return failure == null ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(failure);
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
