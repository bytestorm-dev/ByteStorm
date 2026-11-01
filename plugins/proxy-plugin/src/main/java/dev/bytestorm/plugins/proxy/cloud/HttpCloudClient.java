package dev.bytestorm.plugins.proxy.cloud;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link CloudClient} über das REST-Modul der Cloud (§11, §16).
 *
 * <p>Der Proxy kennt damit nur eine Adresse und den API-Key — kein Redis, kein
 * MongoDB. Jede Anfrage trägt den Key im Header {@value #API_KEY_HEADER}; eine
 * abweichende Antwort wird zur {@link CloudException} mit dem Grund, den die
 * Cloud nennt.
 */
public final class HttpCloudClient implements CloudClient {

    /** Header, den das REST-Modul alternativ zum {@code Authorization}-Header liest. */
    public static final String API_KEY_HEADER = "X-API-Key";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;

    /**
     * @param baseUrl Adresse des REST-Moduls, z. B. {@code http://127.0.0.1:8080}
     * @param apiKey  Key aus der Modul-Konfiguration
     * @param timeout Zeitlimit einer Anfrage
     */
    public HttpCloudClient(String baseUrl, String apiKey, Duration timeout) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public List<ServiceView> services() {
        List<ServiceView> services = new ArrayList<>();
        for (JsonNode node : send("GET", "/services", null).path("services")) {
            services.add(service(node));
        }
        services.sort(Comparator.comparing(ServiceView::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(services);
    }

    @Override
    public List<GroupView> groups() {
        List<GroupView> groups = new ArrayList<>();
        for (JsonNode node : send("GET", "/groups", null).path("groups")) {
            groups.add(new GroupView(
                    node.path("name").asText(""),
                    node.path("type").asText(""),
                    node.path("mode").asText(""),
                    node.path("maintenance").asBoolean(false),
                    node.path("services").asInt(0),
                    node.path("maxServices").asInt(0),
                    node.path("maxPlayers").asInt(0)));
        }
        groups.sort(Comparator.comparing(GroupView::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(groups);
    }

    @Override
    public List<PlayerView> players() {
        List<PlayerView> players = new ArrayList<>();
        for (JsonNode node : send("GET", "/players", null).path("players")) {
            players.add(new PlayerView(
                    node.path("uuid").asText(""),
                    node.path("name").asText(""),
                    node.path("proxy").asText(""),
                    node.path("server").asText("")));
        }
        players.sort(Comparator.comparing(PlayerView::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(players);
    }

    @Override
    public List<String> logs(String serviceId, int lines) {
        JsonNode answer = send("GET", "/services/logs?id=" + serviceId + "&lines=" + lines, null);
        List<String> tail = new ArrayList<>();
        for (JsonNode line : answer.path("lines")) {
            tail.add(line.asText(""));
        }
        return List.copyOf(tail);
    }

    @Override
    public ServiceView start(String group) {
        JsonNode answer = send("POST", "/services/start", Map.of("group", group));
        return service(answer.path("service"));
    }

    @Override
    public void stop(String serviceId, boolean force) {
        send("POST", "/services/stop", Map.of("id", serviceId, "force", force));
    }

    @Override
    public void restart(String serviceId) {
        send("POST", "/services/restart", Map.of("id", serviceId));
    }

    @Override
    public void execute(String serviceId, String command) {
        send("POST", "/services/command", Map.of("id", serviceId, "command", command));
    }

    @Override
    public void reapply(String serviceId) {
        send("POST", "/services/reapply", Map.of("id", serviceId));
    }

    @Override
    public void online(String serviceId) {
        send("POST", "/services/online", Map.of("id", serviceId));
    }

    @Override
    public void maintenance(String group, boolean enabled) {
        send("POST", "/groups/maintenance", Map.of("name", group, "enabled", enabled));
    }

    @Override
    public void scale(String group, int target) {
        send("POST", "/groups/scale", Map.of("name", group, "target", target));
    }

    @Override
    public void connect(String uuid, String service) {
        send("POST", "/players/connect", Map.of("uuid", uuid, "service", service));
    }

    @Override
    public void kick(String uuid, String reason) {
        send("POST", "/players/kick", Map.of("uuid", uuid, "reason", reason));
    }

    private static ServiceView service(JsonNode node) {
        CrashView crash = new CrashView(
                node.path("crashBudgetRemaining").asInt(0),
                node.path("crashBudgetTotal").asInt(0),
                node.path("lastCrashReason").asText(""),
                node.path("lastCrashTime").asLong(0),
                node.path("lastCrashExitCode").asInt(0));
        return new ServiceView(
                node.path("id").asText(""),
                node.path("name").asText(""),
                node.path("group").asText(""),
                node.path("state").asText(""),
                node.path("port").asInt(0),
                node.path("onlinePlayers").asInt(0),
                node.path("maxPlayers").asInt(0),
                node.path("runtime").asText(""),
                crash);
    }

    /**
     * Schickt eine Anfrage und liefert den JSON-Körper.
     *
     * @param method {@code GET} oder {@code POST}
     * @param path   Pfad unterhalb der Basisadresse
     * @param body   Körper für {@code POST}, {@code null} für {@code GET}
     * @return der geparste Körper
     * @throws CloudException wenn die Cloud nicht antwortet, ablehnt oder kein JSON liefert
     */
    private JsonNode send(String method, String path, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header(API_KEY_HEADER, apiKey)
                .header("Accept", "application/json");
        if (body == null) {
            request.GET();
        } else {
            try {
                request.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                JSON.writeValueAsString(body), StandardCharsets.UTF_8));
            } catch (JsonProcessingException e) {
                throw new CloudException("Anfrage konnte nicht gebaut werden: " + e.getOriginalMessage(), e);
            }
        }

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CloudException(baseUrl + " ist nicht erreichbar", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudException("Anfrage abgebrochen", e);
        }

        JsonNode root = parse(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String reason = root.path("error").asText("");
            throw new CloudException(reason.isBlank() ? "HTTP " + response.statusCode() : reason);
        }
        return root;
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(body);
        } catch (JsonProcessingException e) {
            throw new CloudException("Antwort ist kein JSON: " + e.getOriginalMessage(), e);
        }
    }
}
