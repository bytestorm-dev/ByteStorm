package dev.bytestorm.module.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytestorm.api.ByteStormApi;
import dev.bytestorm.api.CloudModule;
import dev.bytestorm.api.module.ModuleContext;
import dev.bytestorm.api.service.CrashBudget;
import dev.bytestorm.api.service.ServiceCrash;
import dev.bytestorm.proto.PlayerInfo;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.StartServiceResponse;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * REST-Modul der Cloud (§11, §18).
 *
 * <p>Es ist der erste echte Konsument der öffentlichen API: Der eingebettete
 * HTTP-Server liest Services, Gruppen, Spieler und Templates ausschließlich über
 * {@link ByteStormApi} — nie aus Redis oder MongoDB. Damit funktioniert dasselbe
 * Modul in der Cloud und, auf der {@code api}-Fassade, in jedem anderen Prozess.
 *
 * <p>Der Lebenszyklus folgt dem der Cloud: {@code onLoad} prüft die Einstellungen,
 * {@code onEnable} startet den Server, {@code onDisable} stoppt ihn. Jede Anfrage
 * braucht einen gültigen API-Key; ohne ihn antwortet der Server mit {@code 401}
 * und ohne weitere Verarbeitung (§11).
 *
 * <p>Fehler verlassen den Server als JSON ({@code {"error": "..."}}) ohne
 * Stacktrace; Details stehen im Modul-Log. Ein falscher Aufruf (fehlendes Feld,
 * ungültige UUID) ist {@code 400}, ein Fehler der Cloud (unbekannter Service,
 * fehlgeschlagener Start) {@code 409}.
 */
public final class RestModule implements CloudModule {

    /** Modulname, unter dem die Cloud es führt. */
    public static final String NAME = "rest";

    /** Pfad des ersten Endpunkts (§11). */
    public static final String SERVICES_PATH = "/services";

    /** {@code POST /services/start} — startet einen Service der Gruppe (§9). */
    public static final String START_PATH = SERVICES_PATH + "/start";

    /** {@code POST /services/stop} — fährt einen Service herunter (§9.2). */
    public static final String STOP_PATH = SERVICES_PATH + "/stop";

    /** {@code POST /services/command} — schickt eine Zeile an die Service-Konsole. */
    public static final String COMMAND_PATH = SERVICES_PATH + "/command";

    /** {@code POST /services/restart} — startet einen Service neu (§9.2). */
    public static final String RESTART_PATH = SERVICES_PATH + "/restart";

    /** {@code GET /services/logs?id=…&lines=…} — letzte Logzeilen eines Services. */
    public static final String LOGS_PATH = SERVICES_PATH + "/logs";

    /** {@code POST /services/reapply} — Templates eines Services erneut anwenden (§10). */
    public static final String REAPPLY_PATH = SERVICES_PATH + "/reapply";

    /** {@code POST /services/online} — Service als {@code ONLINE} melden (§14). */
    public static final String ONLINE_PATH = SERVICES_PATH + "/online";

    /** Ohne {@code lines} liefert die Log-Abfrage so viele Zeilen. */
    static final int DEFAULT_LOG_LINES = 20;

    /** Mehr Zeilen gibt ein einzelner Aufruf nicht heraus. */
    static final int MAX_LOG_LINES = 200;

    /** {@code GET /groups} — alle Gruppen-Definitionen samt laufender Services (§11). */
    public static final String GROUPS_PATH = "/groups";

    /** {@code POST /groups/maintenance} — Wartungsmodus einer Gruppe (§14). */
    public static final String MAINTENANCE_PATH = GROUPS_PATH + "/maintenance";

    /** {@code POST /groups/scale} — Gruppe manuell skalieren (§11). */
    public static final String SCALE_PATH = GROUPS_PATH + "/scale";

    /** {@code GET /players} — verbundene Spieler (§8). */
    public static final String PLAYERS_PATH = "/players";

    /** {@code POST /players/connect} — Spieler auf einen Service schicken (§14). */
    public static final String CONNECT_PATH = PLAYERS_PATH + "/connect";

    /** {@code POST /players/kick} — Spieler vom Netz trennen (§14). */
    public static final String KICK_PATH = PLAYERS_PATH + "/kick";

    /** Wie lange eine Anfrage auf die Cloud wartet, bevor sie aufgibt. */
    static final long TIMEOUT_SECONDS = 30;

    /** Header, den Clients alternativ zum {@code Authorization}-Header nutzen können. */
    public static final String API_KEY_HEADER = "X-API-Key";

    private static final String BEARER = "bearer ";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ModuleContext context;
    private RestSettings settings;
    private Javalin server;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    /**
     * Prüft die Einstellungen, bevor irgendetwas lauscht: Ohne API-Key bleibt das
     * Modul aus, statt die Cloud ungeschützt anzubieten (§11, §20).
     *
     * @param context Zugriff auf API, Datenverzeichnis und Logger
     * @throws IllegalArgumentException wenn die Einstellungen unbrauchbar sind
     */
    @Override
    public void onLoad(ModuleContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.settings = RestSettings.from(context.settings());
        context.logger().info("REST-Modul vorbereitet, hört auf {} (API-Key gesetzt)", settings);
    }

    /**
     * Startet den HTTP-Server. Wiederholte Aufrufe sind wirkungslos.
     *
     * @throws IllegalStateException wenn die API nicht installiert ist
     */
    @Override
    public synchronized void onEnable() {
        if (server != null) {
            return;
        }
        ByteStormApi api = context.api();
        server = Javalin.create(config -> {
            // Die Cloud bringt ihr eigenes Logging mit; das Javalin-Banner stört nur.
            config.startup.showJavalinBanner = false;
            config.jetty.host = settings.host();
            config.jetty.port = settings.port();
            // Pflicht vor jedem Endpunkt, auch vor späteren (§11).
            config.routes.before(this::authorize);
            config.routes.get(SERVICES_PATH, ctx -> services(ctx, api));
            config.routes.post(START_PATH, ctx -> start(ctx, api));
            config.routes.post(STOP_PATH, ctx -> stop(ctx, api));
            config.routes.post(COMMAND_PATH, ctx -> command(ctx, api));
            config.routes.post(RESTART_PATH, ctx -> restart(ctx, api));
            config.routes.get(LOGS_PATH, ctx -> logs(ctx, api));
            config.routes.post(REAPPLY_PATH, ctx -> reapply(ctx, api));
            config.routes.post(ONLINE_PATH, ctx -> online(ctx, api));
            config.routes.get(GROUPS_PATH, ctx -> groups(ctx, api));
            config.routes.post(MAINTENANCE_PATH, ctx -> maintenance(ctx, api));
            config.routes.post(SCALE_PATH, ctx -> scale(ctx, api));
            config.routes.get(PLAYERS_PATH, ctx -> players(ctx, api));
            config.routes.post(CONNECT_PATH, ctx -> connect(ctx, api));
            config.routes.post(KICK_PATH, ctx -> kick(ctx, api));
            config.routes.exception(ApiException.class,
                    (error, ctx) -> error(ctx, error.status(), error.getMessage()));
            config.routes.exception(Exception.class, (error, ctx) -> {
                context.logger().error("REST-Anfrage {} {} fehlgeschlagen",
                        ctx.method(), ctx.path(), error);
                error(ctx, 500, "Interner Fehler");
            });
        }).start();
        context.logger().info("REST-Modul lauscht auf http://{}", settings);
    }

    /**
     * Stoppt den HTTP-Server. Wiederholte Aufrufe sind wirkungslos.
     */
    @Override
    public synchronized void onDisable() {
        Javalin running = server;
        server = null;
        if (running != null) {
            running.stop();
            context.logger().info("REST-Modul gestoppt");
        }
    }

    /**
     * @return {@code true}, wenn der Server gerade lauscht
     */
    public synchronized boolean isRunning() {
        return server != null;
    }

    /**
     * Prüft den Key aus {@code Authorization: Bearer …} oder {@code X-API-Key}.
     *
     * @param ctx Anfrage
     * @throws UnauthorizedException wenn kein gültiger Key mitkam
     */
    private void authorize(Context ctx) {
        String presented = presentedKey(ctx);
        if (!matches(settings.apiKey(), presented)) {
            throw new UnauthorizedException();
        }
    }

    private static String presentedKey(Context ctx) {
        String authorization = ctx.header("Authorization");
        if (authorization != null && authorization.length() >= BEARER.length()
                && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return authorization.substring(BEARER.length()).trim();
        }
        String direct = ctx.header(API_KEY_HEADER);
        return direct == null ? "" : direct.trim();
    }

    /**
     * Vergleicht Keys in konstanter Zeit, damit die Antwortzeit nicht verrät, wie
     * viele Zeichen richtig waren.
     *
     * @param expected  konfigurierter Key
     * @param presented Key aus der Anfrage
     * @return {@code true}, wenn beide übereinstimmen
     */
    private static boolean matches(String expected, String presented) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code GET /services}: alle bekannten Services, alphabetisch nach Namen.
     *
     * @param ctx Anfrage
     * @param api öffentliche API
     */
    private static void services(Context ctx, ByteStormApi api) {
        Map<String, CrashBudget> budgets = budgets(api);
        Map<String, ServiceCrash> crashes = crashes(api);
        List<Map<String, Object>> services = api.services().all().stream()
                .sorted(Comparator.comparing(ServiceInfo::getName, String.CASE_INSENSITIVE_ORDER))
                .map(info -> toJson(info, budgets.get(info.getName()), crashes.get(info.getName())))
                .toList();
        write(ctx, 200, Map.of("services", services));
    }

    /**
     * Liest die Budgets so, dass ein Fehler die Übersicht nicht verhindert.
     *
     * @param api öffentliche API
     * @return Budgets je Service-Name, leer wenn nicht lesbar
     */
    private static Map<String, CrashBudget> budgets(ByteStormApi api) {
        try {
            return api.services().crashBudgets();
        } catch (RuntimeException e) {
            // Ohne Budget bleibt die Spalte leer statt die Antwort zu kippen.
            return Map.of();
        }
    }

    /**
     * @param api öffentliche API
     * @return der letzte Absturz je Service-Name, leer wenn nicht lesbar
     */
    private static Map<String, ServiceCrash> crashes(ByteStormApi api) {
        Map<String, ServiceCrash> latest = new LinkedHashMap<>();
        try {
            // Der Controller liefert neueste zuerst; der erste Eintrag je Service zählt.
            for (ServiceCrash crash : api.services().lastCrashes()) {
                latest.putIfAbsent(crash.service(), crash);
            }
        } catch (RuntimeException e) {
            // Eine unlesbare Crash-Historie darf die Übersicht nicht verhindern.
        }
        return Map.copyOf(latest);
    }

    /**
     * {@code POST /services/start}: startet einen Service der Gruppe. Die
     * Properties aus dem Body gelten für diesen einen Start (§9).
     *
     * @param ctx Anfrage mit {@code group} und optional {@code properties}
     * @param api öffentliche API
     * @throws ApiException wenn der Start fehlschlägt
     */
    private static void start(Context ctx, ByteStormApi api) {
        JsonNode body = request(ctx);
        String group = requiredText(body, "group");
        Map<String, String> properties = properties(body);
        StartServiceResponse response = await(api.services().start(group, properties));
        if (!response.getSuccess() || !response.hasService()) {
            throw new ApiException(409, response.getError().isBlank()
                    ? "Service der Gruppe " + group + " konnte nicht gestartet werden"
                    : response.getError());
        }
        write(ctx, 201, Map.of("service", toJson(response.getService())));
    }

    /**
     * {@code POST /services/stop}: fährt einen Service graceful herunter; mit
     * {@code "force": true} wird der Graceful-Timeout übersprungen.
     *
     * @param ctx Anfrage mit {@code id} und optional {@code force}
     * @param api öffentliche API
     * @throws ApiException wenn der Stop fehlschlägt
     */
    private static void stop(Context ctx, ByteStormApi api) {
        JsonNode body = request(ctx);
        String id = requiredServiceId(body);
        boolean force = body.path("force").asBoolean(false);
        await(api.services().stop(id, force));
        write(ctx, 200, Map.of("id", id, "stopped", true));
    }

    /**
     * {@code POST /services/command}: schickt eine Zeile an die Konsole des
     * Services.
     *
     * @param ctx Anfrage mit {@code id} und {@code command}
     * @param api öffentliche API
     * @throws ApiException wenn der Service nicht läuft oder keine Konsole hat
     */
    private static void command(Context ctx, ByteStormApi api) {
        JsonNode body = request(ctx);
        String id = requiredServiceId(body);
        String command = requiredText(body, "command");
        await(api.services().execute(id, command));
        write(ctx, 200, Map.of("id", id, "sent", command));
    }

    /**
     * {@code GET /services/logs}: letzte Logzeilen eines Services. {@code lines}
     * begrenzt die Ausgabe; ohne Angabe gelten {@value #DEFAULT_LOG_LINES} Zeilen.
     *
     * @param ctx Anfrage mit {@code id} und optional {@code lines}
     * @param api öffentliche API
     * @throws ApiException wenn die Parameter fehlen oder unbrauchbar sind
     */
    private static void logs(Context ctx, ByteStormApi api) {
        String id = requiredQueryUuid(ctx, "id");
        int limit = limit(ctx.queryParam("lines"));
        List<String> lines = api.services().logs(id);
        List<String> tail = lines.size() <= limit
                ? List.copyOf(lines)
                : List.copyOf(lines.subList(lines.size() - limit, lines.size()));
        write(ctx, 200, Map.of("id", id, "lines", tail));
    }

    /**
     * {@code POST /services/restart}: startet einen Service neu.
     *
     * @param ctx Anfrage mit {@code id}
     * @param api öffentliche API
     * @throws ApiException wenn der Neustart scheitert
     */
    private static void restart(Context ctx, ByteStormApi api) {
        JsonNode body = request(ctx);
        String id = requiredServiceId(body);
        await(api.services().restart(id));
        write(ctx, 200, Map.of("id", id, "restarted", true));
    }

    /**
     * {@code POST /services/reapply}: wendet die Templates eines Services erneut an.
     *
     * @param ctx Anfrage mit {@code id}
     * @param api öffentliche API
     * @throws ApiException wenn das Anwenden scheitert
     */
    private static void reapply(Context ctx, ByteStormApi api) {
        JsonNode body = request(ctx);
        String id = requiredServiceId(body);
        await(api.services().reapply(id));
        write(ctx, 200, Map.of("id", id, "reapplied", true));
    }

    /**
     * {@code POST /services/online}: meldet einen Service als {@code ONLINE}.
     *
     * @param ctx Anfrage mit {@code id}
     * @param api öffentliche API
     * @throws ApiException wenn der Service unbekannt ist
     */
    private static void online(Context ctx, ByteStormApi api) {
        JsonNode body = request(ctx);
        String id = requiredServiceId(body);
        await(api.services().online(id));
        write(ctx, 200, Map.of("id", id, "online", true));
    }

    /**
     * {@code GET /groups}: alle Gruppen mit der aktuellen Anzahl ihrer Services.
     *
     * @param ctx Anfrage
     * @param api öffentliche API
     */
    private static void groups(Context ctx, ByteStormApi api) {
        List<ServiceInfo> services = List.copyOf(api.services().all());
        List<Map<String, Object>> groups = api.groups().all().stream()
                .sorted(Comparator.comparing(ServiceGroup::getName, String.CASE_INSENSITIVE_ORDER))
                .map(group -> toJson(group, (int) services.stream()
                        .filter(service -> service.getGroup().equals(group.getName()))
                        .count()))
                .toList();
        write(ctx, 200, Map.of("groups", groups));
    }

    /**
     * {@code POST /groups/maintenance}: schaltet den Wartungsmodus einer Gruppe.
     *
     * @param ctx Anfrage mit {@code name} und {@code enabled}
     * @param api öffentliche API
     * @throws ApiException wenn die Cloud ablehnt
     */
    private static void maintenance(Context ctx, ByteStormApi api) {
        JsonNode body = request(ctx);
        String name = requiredText(body, "name");
        boolean enabled = requiredFlag(body, "enabled");
        await(api.groups().setMaintenance(name, enabled));
        write(ctx, 200, Map.of("group", name, "maintenance", enabled));
    }

    /**
     * {@code POST /groups/scale}: setzt die Anzahl der Services einer Gruppe.
     *
     * @param ctx Anfrage mit {@code name} und {@code target}
     * @param api öffentliche API
     * @throws ApiException wenn die Cloud ablehnt
     */
    private static void scale(Context ctx, ByteStormApi api) {
        JsonNode body = request(ctx);
        String name = requiredText(body, "name");
        int target = requiredCount(body, "target");
        await(api.groups().scale(name, target));
        write(ctx, 200, Map.of("group", name, "target", target));
    }

    /**
     * {@code GET /players}: alle verbundenen Spieler, alphabetisch nach Namen.
     *
     * @param ctx Anfrage
     * @param api öffentliche API
     */
    private static void players(Context ctx, ByteStormApi api) {
        List<Map<String, Object>> players = api.players().online().stream()
                .sorted(Comparator.comparing(PlayerInfo::getName, String.CASE_INSENSITIVE_ORDER))
                .map(RestModule::toJson)
                .toList();
        write(ctx, 200, Map.of("players", players));
    }

    /**
     * {@code POST /players/connect}: schickt einen Spieler auf einen Service.
     *
     * @param ctx Anfrage mit {@code uuid} und {@code service}
     * @param api öffentliche API
     * @throws ApiException wenn das Routing scheitert
     */
    private static void connect(Context ctx, ByteStormApi api) {
        JsonNode body = request(ctx);
        String uuid = requiredUuid(body, "uuid");
        String service = requiredText(body, "service");
        await(api.players().connect(UUID.fromString(uuid), service));
        write(ctx, 200, Map.of("uuid", uuid, "service", service));
    }

    /**
     * {@code POST /players/kick}: trennt einen Spieler vom Netz. Die Begründung
     * ist optional; leer ersetzt die Bridge selbst.
     *
     * @param ctx Anfrage mit {@code uuid} und optional {@code reason}
     * @param api öffentliche API
     * @throws ApiException wenn der Kick scheitert
     */
    private static void kick(Context ctx, ByteStormApi api) {
        JsonNode body = request(ctx);
        String uuid = requiredUuid(body, "uuid");
        String reason = textOf(body, "reason", "");
        await(api.players().kick(UUID.fromString(uuid), reason));
        write(ctx, 200, Map.of("uuid", uuid, "kicked", true));
    }

    /**
     * Liest den JSON-Body als Objekt.
     *
     * @param ctx Anfrage
     * @return der geparste Body
     * @throws ApiException wenn der Body fehlt oder kein JSON-Objekt ist
     */
    private static JsonNode request(Context ctx) {
        String raw = ctx.body();
        if (raw == null || raw.isBlank()) {
            throw new ApiException(400, "JSON-Body fehlt");
        }
        JsonNode body;
        try {
            body = JSON.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new ApiException(400, "JSON-Body ist ungültig");
        }
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "JSON-Body muss ein Objekt sein");
        }
        return body;
    }

    /**
     * @param body JSON-Body
     * @param field erwartetes Feld
     * @return der Text des Felds
     * @throws ApiException wenn das Feld fehlt, leer oder kein einzelner Wert ist
     */
    private static String requiredText(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isValueNode()) {
            throw new ApiException(400, "Feld '" + field + "' fehlt oder ist kein einzelner Wert");
        }
        String text = value.asText().trim();
        if (text.isEmpty()) {
            throw new ApiException(400, "Feld '" + field + "' darf nicht leer sein");
        }
        return text;
    }

    /**
     * @param body JSON-Body
     * @return die Service-UUID aus {@code id}
     * @throws ApiException wenn sie fehlt oder keine UUID ist
     */
    private static String requiredServiceId(JsonNode body) {
        String id = requiredText(body, "id");
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "Feld 'id' ist keine Service-UUID: " + id);
        }
        return id;
    }

    /**
     * @param ctx       Anfrage
     * @param parameter Name des Query-Parameters
     * @return die UUID als String
     * @throws ApiException wenn der Parameter fehlt oder keine UUID ist
     */
    private static String requiredQueryUuid(Context ctx, String parameter) {
        String value = ctx.queryParam(parameter);
        if (value == null || value.isBlank()) {
            throw new ApiException(400, "Parameter '" + parameter + "' fehlt");
        }
        String id = value.trim();
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "Parameter '" + parameter + "' ist keine UUID: " + id);
        }
        return id;
    }

    /**
     * @param raw Wert des Parameters {@code lines}
     * @return die Zeilenzahl zwischen 1 und {@value #MAX_LOG_LINES}
     * @throws ApiException wenn der Wert keine Zahl ist
     */
    private static int limit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LOG_LINES;
        }
        int lines;
        try {
            lines = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, "Parameter 'lines' ist keine Zahl: " + raw);
        }
        return Math.max(1, Math.min(lines, MAX_LOG_LINES));
    }

    /**
     * @param body  JSON-Body
     * @param field erwartetes Feld
     * @return der Schalterwert
     * @throws ApiException wenn das Feld fehlt oder kein Wahrheitswert ist
     */
    private static boolean requiredFlag(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isBoolean()) {
            throw new ApiException(400, "Feld '" + field + "' muss true oder false sein");
        }
        return value.asBoolean();
    }

    /**
     * @param body  JSON-Body
     * @param field erwartetes Feld
     * @return die nicht-negative Zahl
     * @throws ApiException wenn das Feld fehlt, keine ganze Zahl oder negativ ist
     */
    private static int requiredCount(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isIntegralNumber() || value.asInt() < 0) {
            throw new ApiException(400, "Feld '" + field + "' muss eine ganze Zahl ab 0 sein");
        }
        return value.asInt();
    }

    /**
     * @param body  JSON-Body
     * @param field erwartetes Feld
     * @return die UUID als String
     * @throws ApiException wenn das Feld fehlt oder keine UUID ist
     */
    private static String requiredUuid(JsonNode body, String field) {
        String uuid = requiredText(body, field);
        try {
            UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "Feld '" + field + "' ist keine UUID: " + uuid);
        }
        return uuid;
    }

    /**
     * @param body     JSON-Body
     * @param field    optionales Feld
     * @param fallback Wert, wenn das Feld fehlt
     * @return der Text des Felds
     * @throws ApiException wenn das Feld kein einzelner Wert ist
     */
    private static String textOf(JsonNode body, String field, String fallback) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isValueNode()) {
            throw new ApiException(400, "Feld '" + field + "' muss ein einzelner Wert sein");
        }
        return value.asText().trim();
    }

    /**
     * @param body JSON-Body
     * @return die optionalen Properties aus {@code properties}
     * @throws ApiException wenn {@code properties} kein Objekt aus einzelnen Werten ist
     */
    private static Map<String, String> properties(JsonNode body) {
        JsonNode node = body.get("properties");
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new ApiException(400, "Feld 'properties' muss ein Objekt sein");
        }
        Map<String, String> properties = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || !value.isValueNode()) {
                throw new ApiException(400,
                        "Property '" + entry.getKey() + "' muss ein einzelner Wert sein");
            }
            properties.put(entry.getKey(), value.asText());
        });
        return Map.copyOf(properties);
    }

    /**
     * Wartet auf eine Cloud-Aktion. Die Handler laufen auf Jetty-Threads, die
     * Aktionen auf Virtual Threads der Cloud (§15).
     *
     * @param future Aktion
     * @param <T>    Ergebnistyp
     * @return das Ergebnis
     * @throws ApiException wenn die Aktion zu lange braucht, abbricht oder fehlschlägt
     */
    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ApiException(504, "Zeitüberschreitung nach " + TIMEOUT_SECONDS + " Sekunden");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "Anfrage abgebrochen");
        } catch (ExecutionException e) {
            throw new ApiException(409, messageOf(e.getCause() == null ? e : e.getCause()));
        }
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static Map<String, Object> toJson(ServiceInfo info) {
        return toJson(info, null, null);
    }

    private static Map<String, Object> toJson(ServiceInfo info, CrashBudget budget, ServiceCrash crash) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", info.getId());
        json.put("name", info.getName());
        json.put("group", info.getGroup());
        json.put("type", info.getType().name());
        json.put("state", info.getState().name());
        json.put("host", info.getHost());
        json.put("port", info.getPort());
        json.put("onlinePlayers", info.getOnlinePlayers());
        json.put("maxPlayers", info.getMaxPlayers());
        json.put("startedAt", info.getStartedAt());
        json.put("runtime", info.getRuntime().name());
        json.put("fallback", info.getFallback());
        json.put("fallbackPriority", info.getFallbackPriority());
        json.put("properties", info.getPropertiesMap());
        json.put("crashBudgetRemaining", budget == null ? 0 : budget.remaining());
        json.put("crashBudgetTotal", budget == null ? 0 : budget.total());
        json.put("lastCrashReason", crash == null ? "" : crash.reason());
        json.put("lastCrashTime", crash == null ? 0L : crash.time());
        json.put("lastCrashExitCode", crash == null ? 0 : crash.exitCode());
        json.put("crashReport", crash == null ? "" : crash.report());
        return json;
    }

    private static Map<String, Object> toJson(ServiceGroup group, int running) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", group.getName());
        json.put("type", group.getType().name());
        json.put("platform", group.getPlatform());
        json.put("version", group.getVersion());
        json.put("mode", group.getMode().name());
        json.put("maintenance", group.getMaintenance());
        json.put("services", running);
        json.put("minServices", group.getMinServices());
        json.put("maxServices", group.getMaxServices());
        json.put("maxPlayers", group.getMaxPlayers());
        return json;
    }

    private static Map<String, Object> toJson(PlayerInfo player) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("uuid", player.getUuid());
        json.put("name", player.getName());
        json.put("proxy", player.getProxy());
        json.put("server", player.getServer());
        return json;
    }

    /**
     * Schreibt eine Fehlerantwort im einheitlichen Format (§11). Der Grund steht
     * im Log, nicht in der Antwort.
     *
     * @param ctx     Anfrage
     * @param status  HTTP-Status
     * @param message Meldung für den Client
     */
    private static void error(Context ctx, int status, String message) {
        write(ctx, status, Map.of("error", message == null ? "Unbekannter Fehler" : message));
    }

    private static void write(Context ctx, int status, Object payload) {
        try {
            // Bewusst über den eigenen Mapper: Die Antwort hängt so nicht an der
            // JSON-Konfiguration des Servers.
            String body = JSON.writeValueAsString(payload);
            ctx.status(status);
            ctx.contentType("application/json");
            ctx.result(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Antwort konnte nicht als JSON geschrieben werden", e);
        }
    }

    /**
     * Fehler mit HTTP-Status. Javalin beendet die Handler-Kette nur über eine
     * Ausnahme; der eigene Typ hält die Antwort unabhängig von den Vorgaben des
     * Servers. Die Meldung geht an den Client, Details stehen im Modul-Log.
     */
    static class ApiException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    /**
     * Bricht eine Anfrage ohne gültigen Key ab.
     */
    static final class UnauthorizedException extends ApiException {

        private static final long serialVersionUID = 1L;

        UnauthorizedException() {
            super(401, "Ungültiger oder fehlender API-Key");
        }
    }
}
