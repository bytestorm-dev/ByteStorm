package dev.bytestorm.plugins.lobby.display;

import dev.bytestorm.plugins.lobby.LobbyPlugin;
import dev.bytestorm.plugins.lobby.message.Messages;
import dev.bytestorm.plugins.lobby.redis.LiveState;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Die Server-Köpfe in der Lobby (§14).
 *
 * <p>Je Gruppe ein schwebender Kopf, darunter die Hitbox, darüber das Hologramm
 * mit Anzeigename, Status und Hinweis. Die Entities werden nicht gespeichert: Ein
 * Task baut sie auf, sobald der Chunk geladen ist, animiert sie, solange ein
 * Spieler in der Nähe steht, und räumt sie beim Entladen wieder ab. Die Datei
 * bleibt die einzige Quelle der Wahrheit.
 *
 * <p>Ein Klick löst den Wechsel aus: Hat die Gruppe genau einen erreichbaren
 * Service, geht es direkt dorthin; bei mehreren öffnet sich die Auswahl. Status
 * und Spielerzahl kommen aus dem Cache, den {@link #refresh()} alle paar Sekunden
 * asynchron aus Redis nachlädt — der Tick-Task fragt damit nie das Netzwerk.
 */
public final class ServerDisplayService {

    /** Ticks pro Animationsschritt; gleichzeitig die Interpolationsdauer. */
    static final int STEP = 5;

    static final float MODEL_HEIGHT = 1.45f;
    static final float HINT_HEIGHT = 2.4f;
    static final float STATUS_HEIGHT = 2.9f;
    static final float NAME_HEIGHT = 3.5f;
    static final float SKULL_SCALE = 1.5f;
    static final float NAME_SCALE = 2.7f;
    static final float STATUS_SCALE = 1.6f;
    static final float HINT_SCALE = 1.6f;

    /** Zwei Events derselben Klickbewegung dürfen nicht doppelt zählen. */
    private static final long INTERACTION_GUARD_NANOS = 100_000_000L;

    private final LobbyPlugin plugin;
    private final LiveState live;
    private final DisplayStore store;
    private final Messages messages;
    private final ServerSelectMenu menu;

    private final Map<Integer, ServerDisplay> displays = new ConcurrentHashMap<>();
    private final Map<Integer, Live> entities = new ConcurrentHashMap<>();
    /** Entity-UUID → Anzeige; deckt Kopf, Texte und Hitbox ab. */
    private final Map<UUID, Integer> byEntity = new ConcurrentHashMap<>();
    /** Anzeige-ID → zuletzt gesetzte Statuszeile, damit nur Änderungen schreiben. */
    private final Map<Integer, String> appliedStatus = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastInteraction = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSwitch = new ConcurrentHashMap<>();

    /** Zuletzt gelesene Services, nach Gruppe (Schlüssel kleingeschrieben). */
    private volatile Map<String, List<ServiceInfo>> groups = Map.of();

    private BukkitTask animationTask;
    private BukkitTask refreshTask;
    private long tick;

    /**
     * @param plugin   Plugin für Scheduler und Logger
     * @param live     Lesezugriff auf die Registry
     * @param store    Persistenz der Anzeigen
     * @param messages Sprachdateien für Status, Hinweis und Meldungen
     */
    public ServerDisplayService(LobbyPlugin plugin, LiveState live, DisplayStore store,
                                Messages messages) {
        this.plugin = plugin;
        this.live = live;
        this.store = store;
        this.messages = messages;
        this.menu = new ServerSelectMenu(this, messages);
        for (ServerDisplay display : store.load()) {
            displays.put(display.id(), display);
        }
    }

    /**
     * Startet Animation und das asynchrone Nachladen der Service-Liste.
     */
    public void start() {
        animationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::run, 20L, STEP);
        long refreshTicks = Math.max(20L, plugin.settings().refreshSeconds() * 20L);
        refreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::refresh, 20L, refreshTicks);
    }

    /**
     * Legt eine Anzeige an, speichert sie und baut sie sofort auf.
     *
     * @param group       Gruppenname
     * @param displayName Anzeigename, MiniMessage
     * @param headId      Textur-URL oder {@code null}
     * @param location    Standort des Sockels
     * @return die angelegte Anzeige
     */
    public ServerDisplay create(String group, String displayName, @Nullable String headId,
                                Location location) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Standort ohne Welt");
        }

        int id = displays.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        ServerDisplay display = new ServerDisplay(id, group, displayName, headId,
                world.getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());

        displays.put(id, display);
        store.save(displays.values());
        spawn(display);
        return display;
    }

    /**
     * @param id Anzeige-ID
     * @return {@code true}, wenn die Anzeige existierte und entfernt wurde
     */
    public boolean delete(int id) {
        ServerDisplay display = displays.remove(id);
        if (display == null) {
            return false;
        }
        despawn(display);
        store.save(displays.values());
        return true;
    }

    /**
     * Verschiebt eine Anzeige an einen neuen Standort.
     *
     * @param id       Anzeige-ID
     * @param location neuer Standort
     * @return {@code true}, wenn die Anzeige existierte
     */
    public boolean move(int id, Location location) {
        ServerDisplay current = displays.get(id);
        if (current == null) {
            return false;
        }
        despawn(current);
        ServerDisplay moved = current.movedTo(location);
        displays.put(id, moved);
        store.save(displays.values());
        spawn(moved);
        return true;
    }

    /**
     * Liest {@code displays.yml} neu und baut alle Anzeigen neu auf.
     */
    public void reload() {
        displays.values().forEach(this::despawn);
        displays.clear();
        appliedStatus.clear();
        for (ServerDisplay display : store.load()) {
            displays.put(display.id(), display);
        }
        refresh();
    }

    /**
     * @return alle Anzeigen, unsortiert
     */
    public Collection<ServerDisplay> displays() {
        return List.copyOf(displays.values());
    }

    /**
     * @return die Gruppen aus der Registry und den gespeicherten Anzeigen
     */
    public List<String> groupNames() {
        List<String> names = new ArrayList<>(displays.values().stream()
                .map(ServerDisplay::group)
                .toList());
        groups.values().stream()
                .flatMap(List::stream)
                .map(ServiceInfo::getGroup)
                .forEach(names::add);

        return names.stream()
                .filter(name -> !name.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /**
     * @param entity Kopf, Text oder Hitbox
     * @return die zugehörige Anzeige oder {@code null}
     */
    public @Nullable ServerDisplay display(Entity entity) {
        Integer id = byEntity.get(entity.getUniqueId());
        return id == null ? null : displays.get(id);
    }

    /**
     * Führt einen Klick aus. Ein Service in der Gruppe: direkt verbinden. Mehrere:
     * Auswahlmenü öffnen. Keiner: Meldung, dass die Gruppe offline ist.
     *
     * @param player  klickender Spieler
     * @param display angeklickte Anzeige
     */
    public void join(Player player, ServerDisplay display) {
        long now = System.nanoTime();
        Long previous = lastInteraction.get(player.getUniqueId());
        if (previous != null && now - previous < INTERACTION_GUARD_NANOS) {
            return;
        }
        lastInteraction.put(player.getUniqueId(), now);

        List<ServiceInfo> online = onlineServices(display.group());
        if (online.isEmpty()) {
            message(player, "join.offline", display);
            return;
        }
        if (online.size() == 1) {
            connect(player, online.get(0).getName(), display);
            return;
        }
        menu.open(player, display, online);
    }

    /**
     * Schickt einen Spieler auf einen Service und meldet das Ergebnis.
     *
     * @param player  Spieler
     * @param service Service-Name des Ziels
     * @param display Anzeige, aus der der Klick kam
     * @return {@code true}, wenn die Anfrage abgeschickt wurde
     */
    public boolean connect(Player player, String service, ServerDisplay display) {
        long millis = System.currentTimeMillis();
        Long switched = lastSwitch.get(player.getUniqueId());
        if (switched != null && millis - switched < plugin.settings().switchCooldownMillis()) {
            return false;
        }
        lastSwitch.put(player.getUniqueId(), millis);

        message(player, "join.connecting", display);

        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            if (!live.requestSwitch(uuid, name, service)) {
                Bukkit.getGlobalRegionScheduler().execute(plugin,
                        () -> message(player, "join.unreachable", display));
            }
        });
        return true;
    }

    /**
     * Räumt alle Entities ab und stoppt die Tasks.
     */
    public void shutdown() {
        if (animationTask != null) {
            animationTask.cancel();
            animationTask = null;
        }
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        displays.values().forEach(this::despawn);
    }

    // --- Tick ------------------------------------------------------------------------------------

    private void run() {
        tick += STEP;

        for (ServerDisplay display : displays.values()) {
            Location location = display.location();
            if (location == null || !isReady(location)) {
                despawn(display);
                continue;
            }

            Live current = entities.get(display.id());
            if (current == null || !current.isValid()) {
                spawn(display);
                current = entities.get(display.id());
                if (current == null) {
                    continue;
                }
            }

            updateStatus(display, current);

            World world = location.getWorld();
            if (world == null || world.getNearbyPlayers(location,
                    plugin.settings().animationRadius()).isEmpty()) {
                continue;
            }
            animate(current);
        }
    }

    /** Lädt die Service-Liste; läuft asynchron, weil Redis blockiert. */
    private void refresh() {
        try {
            Map<String, List<ServiceInfo>> snapshot = new HashMap<>();
            for (ServiceInfo info : live.services()) {
                if (!info.getName().isBlank()) {
                    snapshot.computeIfAbsent(info.getGroup().toLowerCase(Locale.ROOT),
                            key -> new ArrayList<>(4)).add(info);
                }
            }
            Map<String, List<ServiceInfo>> copy = new HashMap<>(snapshot.size());
            snapshot.forEach((group, services) -> copy.put(group, List.copyOf(services)));
            groups = Map.copyOf(copy);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Service-Liste nicht gelesen: " + e.getMessage());
        }
    }

    /**
     * @param group Gruppenname
     * @return erreichbare Services der Gruppe, alphabetisch
     */
    private List<ServiceInfo> onlineServices(@Nullable String group) {
        if (group == null || group.isBlank()) {
            return List.of();
        }
        List<ServiceInfo> services = groups.get(group.toLowerCase(Locale.ROOT));
        if (services == null) {
            return List.of();
        }
        return services.stream()
                .filter(service -> service.getState() == ServiceState.ONLINE)
                .sorted(Comparator.comparing(ServiceInfo::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private String statusText(ServerDisplay display) {
        List<ServiceInfo> services = groups.getOrDefault(
                display.group().toLowerCase(Locale.ROOT), List.of());
        return DisplayStatus.text(messages, services);
    }

    /** Setzt die Statuszeile nur, wenn sich der Text geändert hat. */
    private void updateStatus(ServerDisplay display, Live live) {
        String text = statusText(display);
        if (text.equals(appliedStatus.get(display.id()))) {
            return;
        }
        appliedStatus.put(display.id(), text);
        live.status().text(MiniMessage.miniMessage().deserialize(text));
    }

    private boolean isReady(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        return world.isChunkLoaded(chunkX, chunkZ)
                && world.getChunkAt(chunkX, chunkZ, false).isEntitiesLoaded();
    }

    // --- Entities --------------------------------------------------------------------------------

    private void spawn(ServerDisplay display) {
        despawn(display);

        Location base = display.location();
        if (base == null) {
            return;
        }
        World world = base.getWorld();
        if (world == null) {
            return;
        }

        ItemDisplay model = world.spawn(base.clone().add(0, MODEL_HEIGHT, 0), ItemDisplay.class, entity -> {
            entity.setItemStack(Skulls.head(display.headId()));
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            prepare(entity);
        });

        TextDisplay name = spawnText(base.clone().add(0, NAME_HEIGHT, 0), display.displayName(), NAME_SCALE);
        TextDisplay status = spawnText(base.clone().add(0, STATUS_HEIGHT, 0),
                statusText(display), STATUS_SCALE);
        TextDisplay hint = spawnText(base.clone().add(0, HINT_HEIGHT, 0),
                messages.text("display.hint"), HINT_SCALE);

        Interaction hitbox = world.spawn(base.clone(), Interaction.class, interaction -> {
            interaction.setPersistent(false);
            interaction.setInteractionWidth(1.2f);
            interaction.setInteractionHeight(2.4f);
            interaction.setResponsive(true);
        });

        Live live = new Live(model, name, status, hint, hitbox);
        entities.put(display.id(), live);
        appliedStatus.put(display.id(), statusText(display));
        live.all().forEach(entity -> byEntity.put(entity.getUniqueId(), display.id()));

        animate(live);
    }

    private void despawn(ServerDisplay display) {
        appliedStatus.remove(display.id());
        Live current = entities.remove(display.id());
        if (current == null) {
            return;
        }
        current.all().forEach(entity -> {
            byEntity.remove(entity.getUniqueId());
            if (entity.isValid()) {
                entity.remove();
            }
        });
    }

    private void prepare(Display display) {
        display.setPersistent(false);
        display.setBrightness(new Display.Brightness(15, 15));
    }

    private TextDisplay spawnText(Location location, @Nullable String text, float scale) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalStateException("Text ohne Welt");
        }
        return world.spawn(location, TextDisplay.class, display -> {
            prepare(display);
            display.text(MiniMessage.miniMessage().deserialize(text == null ? "" : text));
            display.setBillboard(Display.Billboard.VERTICAL);
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.setShadowed(true);
            display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(scale), new Quaternionf()));
        });
    }

    /** Wie die Crate-Vorschau: eine Drehung pro Sekunde, Schweben mit sin(t / 10) * 0.1. */
    private void animate(Live live) {
        float rotation = tick / 20f;
        float bob = (float) Math.sin(tick / 10D) * 0.1f;

        ItemDisplay model = live.model();
        model.setInterpolationDelay(0);
        model.setInterpolationDuration(STEP);
        model.setTransformation(new Transformation(
                new Vector3f(0, bob, 0),
                new Quaternionf().rotateY(rotation),
                new Vector3f(SKULL_SCALE),
                new Quaternionf()));
    }

    /**
     * Schickt eine Meldung aus den Sprachdateien, mit dem Anzeigenamen als {@code %server%}.
     *
     * @param player  Empfänger
     * @param key     Schlüssel unter {@code join.}
     * @param display angeklickte Anzeige
     */
    private void message(Player player, String key, ServerDisplay display) {
        if (!player.isOnline()) {
            return;
        }
        String rendered = messages.prefixed(key, Map.of("server", display.displayName()));
        player.sendMessage(MiniMessage.miniMessage().deserialize(rendered));
    }

    /**
     * @param model  schwebender Kopf
     * @param name   Anzeigename
     * @param status Statuszeile
     * @param hint   Hinweiszeile
     * @param hitbox Klickfläche
     */
    record Live(ItemDisplay model, TextDisplay name, TextDisplay status, TextDisplay hint,
                Interaction hitbox) {

        List<Entity> all() {
            return List.of(model, name, status, hint, hitbox);
        }

        boolean isValid() {
            return all().stream().allMatch(Entity::isValid);
        }
    }
}
