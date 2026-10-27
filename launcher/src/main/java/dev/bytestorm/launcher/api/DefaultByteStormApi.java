package dev.bytestorm.launcher.api;

import com.google.protobuf.Message;
import dev.bytestorm.api.ByteStormApi;
import dev.bytestorm.api.event.Events;
import dev.bytestorm.api.event.Subscription;
import dev.bytestorm.api.group.Groups;
import dev.bytestorm.api.module.Modules;
import dev.bytestorm.api.player.Players;
import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.api.service.Services;
import dev.bytestorm.api.template.Templates;
import dev.bytestorm.controller.ServiceController;
import dev.bytestorm.controller.group.GroupManager;
import dev.bytestorm.controller.group.GroupScaler;
import dev.bytestorm.controller.player.PlayerManager;
import dev.bytestorm.controller.scaling.ScalingActions;
import dev.bytestorm.controller.template.TemplateManager;
import dev.bytestorm.core.mongo.model.StoredPlayer;
import dev.bytestorm.core.mongo.repository.PlayerRepository;
import dev.bytestorm.proto.PlayerInfo;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.StartServiceResponse;
import dev.bytestorm.proto.TemplateInfo;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Cloud-seitige Umsetzung der öffentlichen API (§15).
 *
 * <p>Die Fassade ist reine Weiterleitung: Lesende Aufrufe kommen aus den
 * Verwaltungen, schreibende laufen auf Virtual Threads, damit die aufrufende
 * Seite — später das REST-Modul, heute die Konsole — nicht blockiert. Fehler
 * werden zu einem unvollständigen Future; bei {@code services().start(...)}
 * landen sie im Proto-Ergebnis ({@code success=false}, {@code error}), weil der
 * Vertrag das so vorsieht.
 *
 * <p>Die Abhängigkeiten sind bewusst schmal gehalten ({@link PlayerAccess},
 * {@link EventRegistrar}): So bleibt die Fassade ohne Redis, Mongo oder Proxy
 * prüfbar, obwohl sie später die Grundlage für Module und REST ist.
 *
 * <p>Zusätzliche {@code properties} beim Start eines Service
 * ({@link Services#start(String, Map)}) gehen an den Provisioner und von dort in
 * die Service-Definition dieses Starts (§9).
 */
public final class DefaultByteStormApi implements ByteStormApi, AutoCloseable {

    /** Timeout für einen graceful Stop über die API. */
    public static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    /** Registriert ein Event-Abonnement, z. B. {@code eventBus::subscribe}. */
    @FunctionalInterface
    public interface EventRegistrar {

        /**
         * @param type     Proto-Nachrichtentyp
         * @param filter   Prädikat vor dem Listener
         * @param listener Empfänger
         * @param <T>      Nachrichtentyp
         * @return Handle zum Beenden des Abonnements
         */
        <T extends Message> Subscription register(Class<T> type, Predicate<T> filter, Consumer<T> listener);
    }

    /** Eine Spieler-Aktion, die den Spielernamen braucht (Verbindung, Kick). */
    @FunctionalInterface
    public interface PlayerCommand {

        /**
         * @param player     Spielername
         * @param argument   Ziel-Service bzw. Begründung
         * @return der angesprochene Proxy
         */
        String run(String player, String argument);
    }

    /**
     * Schmale Sicht auf die Spieler: Live-Zustand aus Redis, Historie aus MongoDB
     * und die beiden Aktionen über den {@link PlayerManager}.
     *
     * @param online Live-Zustand
     * @param byName Historie nach Name
     * @param byUuid Historie nach UUID
     * @param all    gesamte Historie
     * @param connect Verbindung umschalten
     * @param kick    Verbindung trennen
     */
    public record PlayerAccess(
            Supplier<Collection<PlayerInfo>> online,
            Function<String, Optional<StoredPlayer>> byName,
            Function<UUID, Optional<StoredPlayer>> byUuid,
            Supplier<List<StoredPlayer>> all,
            PlayerCommand connect,
            PlayerCommand kick) {

        /**
         * @param manager    Spieler-Verwaltung für Live-Zustand und Aktionen
         * @param repository Historie aus MongoDB
         * @return die Zugriffe für die Fassade
         */
        public static PlayerAccess of(PlayerManager manager, PlayerRepository repository) {
            Objects.requireNonNull(manager, "manager");
            Objects.requireNonNull(repository, "repository");
            return new PlayerAccess(manager::online, repository::findByName, repository::find,
                    repository::findAll, manager::connect, manager::kick);
        }
    }

    private final ServiceController controller;
    private final ScalingActions scaler;
    private final GroupManager groupManager;
    private final GroupScaler groupScaler;
    private final PlayerAccess playerAccess;
    private final TemplateManager templateManager;
    private final Modules moduleFacade;
    private final EventRegistrar eventRegistrar;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    private final Services services = new ServicesAdapter();
    private final Players players = new PlayersAdapter();
    private final Groups groups = new GroupsAdapter();
    private final Events events = new EventsAdapter();
    private final Templates templates = new TemplatesAdapter();

    /**
     * @param controller      Service-Fassade für Zustand, Stop, Neustart und Befehle
     * @param scaler          Start/Stop über den Provisioner (§9)
     * @param groupManager    Gruppen-Verwaltung mit Validierung und Cache
     * @param groupScaler     manuelles Skalieren (§11)
     * @param playerAccess    Spieler-Zugriffe
     * @param templateManager Template-Verwaltung
     * @param modules         Modul-Verwaltung, typischerweise der {@code ModuleManager}
     * @param events          Event-Abonnements, typischerweise der {@code EventBus}
     */
    public DefaultByteStormApi(ServiceController controller,
                               ScalingActions scaler,
                               GroupManager groupManager,
                               GroupScaler groupScaler,
                               PlayerAccess playerAccess,
                               TemplateManager templateManager,
                               Modules modules,
                               EventRegistrar events) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.scaler = Objects.requireNonNull(scaler, "scaler");
        this.groupManager = Objects.requireNonNull(groupManager, "groupManager");
        this.groupScaler = Objects.requireNonNull(groupScaler, "groupScaler");
        this.playerAccess = Objects.requireNonNull(playerAccess, "playerAccess");
        this.templateManager = Objects.requireNonNull(templateManager, "templateManager");
        this.moduleFacade = Objects.requireNonNull(modules, "modules");
        this.eventRegistrar = Objects.requireNonNull(events, "events");
    }

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
    public Events events() {
        return events;
    }

    @Override
    public Templates templates() {
        return templates;
    }

    @Override
    public Modules modules() {
        return moduleFacade;
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }

    /**
     * Führt Arbeit auf einem Virtual Thread aus und verpackt Fehler in ein
     * unvollständiges Future.
     *
     * @param work Arbeit
     * @param <T>  Ergebnistyp
     * @return Future mit dem Ergebnis
     */
    private <T> CompletableFuture<T> async(Callable<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.call();
            } catch (CompletionException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new CompletionException(e);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, workers);
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static String requireServiceId(String serviceId) {
        Objects.requireNonNull(serviceId, "serviceId");
        if (serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId darf nicht leer sein");
        }
        return serviceId;
    }

    /** Services: lesend aus dem Controller, schreibend über die Cloud (§15). */
    private final class ServicesAdapter implements Services {

        @Override
        public Collection<ServiceInfo> all() {
            return List.copyOf(controller.services());
        }

        @Override
        public List<ServiceInfo> byGroup(String group) {
            return List.copyOf(controller.byGroup(Objects.requireNonNull(group, "group")));
        }

        @Override
        public Optional<ServiceInfo> byName(String name) {
            return controller.byName(Objects.requireNonNull(name, "name"));
        }

        @Override
        public Optional<ServiceInfo> byId(String serviceId) {
            String id = requireServiceId(serviceId);
            return controller.services().stream()
                    .filter(info -> info.getId().equals(id))
                    .findFirst();
        }

        @Override
        public CompletableFuture<StartServiceResponse> start(String group, Map<String, String> properties) {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(properties, "properties");
            return async(() -> {
                try {
                    ServiceInfo info = scaler.start(group, properties);
                    return StartServiceResponse.newBuilder().setSuccess(true).setService(info).build();
                } catch (Exception e) {
                    return StartServiceResponse.newBuilder()
                            .setSuccess(false)
                            .setError(messageOf(e))
                            .build();
                }
            });
        }

        @Override
        public CompletableFuture<Void> stop(String serviceId, boolean force) {
            String requested = requireServiceId(serviceId);
            return async(() -> {
                controller.stop(ServiceId.parse(requested), STOP_TIMEOUT, force);
                return null;
            });
        }

        @Override
        public CompletableFuture<Void> restart(String serviceId) {
            String requested = requireServiceId(serviceId);
            return async(() -> {
                controller.restart(ServiceId.parse(requested), STOP_TIMEOUT);
                return null;
            });
        }

        @Override
        public CompletableFuture<Void> execute(String serviceId, String command) {
            String requested = requireServiceId(serviceId);
            String line = Objects.requireNonNull(command, "command");
            return async(() -> {
                controller.sendCommand(ServiceId.parse(requested), line);
                return null;
            });
        }
    }

    /** Spieler: Live-Zustand zuerst, sonst die Historie (§8, §15). */
    private final class PlayersAdapter implements Players {

        @Override
        public Collection<PlayerInfo> online() {
            return List.copyOf(playerAccess.online().get());
        }

        @Override
        public Collection<PlayerInfo> all() {
            Map<String, PlayerInfo> merged = new LinkedHashMap<>();
            for (PlayerInfo info : playerAccess.online().get()) {
                merged.put(info.getUuid(), info);
            }
            for (StoredPlayer stored : playerAccess.all().get()) {
                merged.putIfAbsent(stored.uuid().toString(), stored.toProto());
            }
            return List.copyOf(merged.values());
        }

        @Override
        public Optional<PlayerInfo> byUuid(UUID uuid) {
            Objects.requireNonNull(uuid, "uuid");
            for (PlayerInfo info : playerAccess.online().get()) {
                if (info.getUuid().equals(uuid.toString())) {
                    return Optional.of(info);
                }
            }
            return playerAccess.byUuid().apply(uuid).map(StoredPlayer::toProto);
        }

        @Override
        public Optional<PlayerInfo> byName(String name) {
            Objects.requireNonNull(name, "name");
            for (PlayerInfo info : playerAccess.online().get()) {
                if (info.getName().equalsIgnoreCase(name)) {
                    return Optional.of(info);
                }
            }
            return playerAccess.byName().apply(name).map(StoredPlayer::toProto);
        }

        @Override
        public CompletableFuture<Void> connect(UUID uuid, String service) {
            Objects.requireNonNull(service, "service");
            return async(() -> {
                playerAccess.connect().run(nameOf(uuid), service);
                return null;
            });
        }

        @Override
        public CompletableFuture<Void> kick(UUID uuid, String reason) {
            return async(() -> {
                playerAccess.kick().run(nameOf(uuid), reason == null ? "" : reason);
                return null;
            });
        }

        @Override
        public CompletableFuture<Duration> playtime(UUID uuid) {
            Objects.requireNonNull(uuid, "uuid");
            return async(() -> playerAccess.byUuid().apply(uuid)
                    .map(StoredPlayer::playtime)
                    .orElse(Duration.ZERO));
        }

        private String nameOf(UUID uuid) {
            Objects.requireNonNull(uuid, "uuid");
            for (PlayerInfo info : playerAccess.online().get()) {
                if (info.getUuid().equals(uuid.toString()) && !info.getName().isBlank()) {
                    return info.getName();
                }
            }
            return playerAccess.byUuid().apply(uuid)
                    .map(StoredPlayer::name)
                    .orElseThrow(() -> new IllegalArgumentException("Unbekannter Spieler: " + uuid));
        }
    }

    /** Gruppen: lesend aus dem Cache, schreibend über den Manager (§9.1). */
    private final class GroupsAdapter implements Groups {

        @Override
        public Collection<ServiceGroup> all() {
            return List.copyOf(groupManager.all());
        }

        @Override
        public Optional<ServiceGroup> get(String name) {
            return groupManager.get(Objects.requireNonNull(name, "name"));
        }

        @Override
        public CompletableFuture<Void> create(ServiceGroup group) {
            Objects.requireNonNull(group, "group");
            return async(() -> {
                groupManager.create(group);
                return null;
            });
        }

        @Override
        public CompletableFuture<Void> update(ServiceGroup group) {
            Objects.requireNonNull(group, "group");
            return async(() -> {
                groupManager.update(group);
                return null;
            });
        }

        @Override
        public CompletableFuture<Void> delete(String name) {
            String group = Objects.requireNonNull(name, "name");
            return async(() -> {
                if (!groupManager.delete(group)) {
                    throw new IllegalArgumentException("Unbekannte Gruppe: " + group);
                }
                return null;
            });
        }

        @Override
        public CompletableFuture<Void> setMaintenance(String name, boolean maintenance) {
            String group = Objects.requireNonNull(name, "name");
            return async(() -> {
                groupManager.setMaintenance(group, maintenance);
                return null;
            });
        }

        @Override
        public CompletableFuture<Void> scale(String name, int targetServices) {
            String group = Objects.requireNonNull(name, "name");
            return async(() -> {
                groupScaler.scaleTo(group, targetServices);
                return null;
            });
        }
    }

    /** Events: Abos laufen über den Event-Bus, also über Redisson-Topics (§7). */
    private final class EventsAdapter implements Events {

        @Override
        public <T extends Message> Subscription subscribe(Class<T> type, Predicate<T> filter, Consumer<T> listener) {
            return eventRegistrar.register(Objects.requireNonNull(type, "type"),
                    Objects.requireNonNull(filter, "filter"),
                    Objects.requireNonNull(listener, "listener"));
        }
    }

    /** Templates: Metadaten aus {@code templates/}, Aktionen über den Manager (§10). */
    private final class TemplatesAdapter implements Templates {

        @Override
        public Collection<TemplateInfo> list() {
            return templateManager.list().stream().map(DefaultByteStormApi::toInfo).toList();
        }

        @Override
        public Optional<TemplateInfo> get(String name) {
            return templateManager.get(Objects.requireNonNull(name, "name"))
                    .map(DefaultByteStormApi::toInfo);
        }

        @Override
        public CompletableFuture<Void> create(String name) {
            String template = Objects.requireNonNull(name, "name");
            return async(() -> {
                templateManager.create(template);
                return null;
            });
        }

        @Override
        public CompletableFuture<Void> delete(String name) {
            String template = Objects.requireNonNull(name, "name");
            return async(() -> {
                if (!templateManager.delete(template)) {
                    throw new IllegalArgumentException("Unbekanntes Template: " + template);
                }
                return null;
            });
        }

        @Override
        public CompletableFuture<Void> sync(String serviceId, String template) {
            String requested = requireServiceId(serviceId);
            String target = Objects.requireNonNull(template, "template");
            return async(() -> {
                // Der Manager spiegelt den Ordner eines Services; den Namen liefert die Registry.
                ServiceInfo info = controller.services().stream()
                        .filter(service -> service.getId().equals(requested))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unbekannter Service: " + requested));
                templateManager.sync(info.getName(), target);
                return null;
            });
        }
    }

    private static TemplateInfo toInfo(TemplateManager.TemplateEntry entry) {
        return TemplateInfo.newBuilder()
                .setName(entry.name())
                .setLastModified(entry.lastModified().toEpochMilli())
                .build();
    }
}
