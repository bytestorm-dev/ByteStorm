package dev.bytestorm.core.module;

import dev.bytestorm.api.ByteStormApi;
import dev.bytestorm.api.CloudModule;
import dev.bytestorm.api.module.AccessGrant;
import dev.bytestorm.api.module.ModuleContext;
import dev.bytestorm.api.module.Modules;
import dev.bytestorm.core.mongo.model.ModuleConfig;
import dev.bytestorm.proto.ModuleInfo;
import dev.bytestorm.proto.ModuleState;
import dev.bytestorm.proto.ModuleStateChanged;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Lädt und schaltet die optionalen Module (§16).
 *
 * <p>Module werden über {@link ServiceLoader} gefunden: Jede Jar in {@code lib/},
 * die einen {@code META-INF/services/dev.bytestorm.api.CloudModule}-Eintrag
 * mitbringt, ist ein Kandidat. Der Lebenszyklus ist dreistufig —
 * {@code onLoad} einmalig, danach {@code onEnable}/{@code onDisable} bei jedem
 * Umschalten. Ein Fehler in einem Modul bleibt in diesem Modul: Der Zustand wird
 * {@code FAILED}, die Cloud läuft weiter.
 *
 * <p>Der Schaltzustand liegt in MongoDB ({@code modules_config}); beim Start
 * werden alle dort aktivierten Module geladen. Die Einstellungen eines Moduls
 * liest {@link ModuleContext#settings()} aus derselben Quelle, das
 * Datenverzeichnis liegt unter {@code config/modules/<name>/}. Dieselbe
 * Konfiguration schreibt {@link #setSetting(String, String, String)}: So kommt
 * z. B. der API-Key des REST-Moduls über die CLI oder den Wizard nach MongoDB,
 * ohne dass jemand die Collection von Hand bearbeitet.
 *
 * <p>{@link ModuleContext#api()} liefert die öffentliche API
 * ({@code ByteStorm.api()}); ist sie nicht installiert, scheitert der Zugriff mit
 * derselben Meldung wie überall sonst.
 *
 * <p>Jeder Zustandswechsel geht als {@link ModuleStateChanged} an den Publisher,
 * in der Cloud der {@code EventBus} (Topic {@code bs:event:bytestorm.ModuleStateChanged}).
 * Damit sieht z. B. das Discord-Modul ein- und ausgeschaltete Module, ohne den
 * {@code ModuleManager} zu kennen. Ein fehlgeschlagener Publish bleibt folgenlos:
 * das Modul ist bereits umgeschaltet, die Meldung ist nur eine Benachrichtigung.
 */
@Slf4j
public final class ModuleManager implements Modules, AutoCloseable {

    private final ModuleStore store;
    private final Path modulesDirectory;
    private final Supplier<ByteStormApi> api;
    private final Consumer<ModuleStateChanged> events;
    /** Längster zulässiger Wert in der Modul-Konfiguration (Token, Schlüssel, Pfade). */
    private static final int MAX_VALUE_LENGTH = 512;

    private final Map<String, Holder> holders = new ConcurrentHashMap<>();
    private final ExecutorService lifecycle = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * @param store            Persistenz der Modul-Konfigurationen
     * @param modulesDirectory Modul-Datenverzeichnis, üblicherweise {@code config/modules/}
     * @param api              Quelle der öffentlichen API, z. B. {@code ByteStorm::api}
     * @param events           Publisher für Zustandswechsel, z. B. {@code eventBus::publish}
     */
    public ModuleManager(ModuleStore store, Path modulesDirectory, Supplier<ByteStormApi> api,
                         Consumer<ModuleStateChanged> events) {
        this(store, modulesDirectory, api, ModuleManager::discover, events);
    }

    /**
     * @param store            Persistenz der Modul-Konfigurationen
     * @param modulesDirectory Modul-Datenverzeichnis
     * @param api              Quelle der öffentlichen API
     * @param discovery        Quelle der Modul-Implementierungen, z. B. der ServiceLoader
     * @param events           Publisher für Zustandswechsel
     */
    ModuleManager(ModuleStore store, Path modulesDirectory, Supplier<ByteStormApi> api,
                  Supplier<List<CloudModule>> discovery, Consumer<ModuleStateChanged> events) {
        this.store = Objects.requireNonNull(store, "store");
        this.modulesDirectory = Objects.requireNonNull(modulesDirectory, "modulesDirectory")
                .toAbsolutePath().normalize();
        this.api = Objects.requireNonNull(api, "api");
        this.events = Objects.requireNonNull(events, "events");
        for (CloudModule module : Objects.requireNonNull(discovery, "discovery").get()) {
            register(module);
        }
    }

    /**
     * Aktiviert alle in MongoDB aktivierten Module.
     *
     * <p>Läuft synchron, damit der Start eine klare Meldung zeigt; ein fehlerhaftes
     * Modul verhindert den Start der Cloud nicht.
     */
    public void start() {
        for (ModuleConfig config : store.findAll()) {
            if (!config.enabled()) {
                continue;
            }
            if (!holders.containsKey(config.name())) {
                log.warn("Modul '{}' ist aktiviert, liegt aber nicht in lib/ — übersprungen", config.name());
                continue;
            }
            try {
                load(config.name());
            } catch (RuntimeException e) {
                log.warn("Modul '{}' konnte nicht geladen werden: {}", config.name(), e.getMessage());
            }
        }
    }

    @Override
    public Collection<ModuleInfo> list() {
        Map<String, ModuleInfo> infos = new TreeMap<>();
        for (Map.Entry<String, Holder> entry : holders.entrySet()) {
            infos.put(entry.getKey(), info(entry.getValue()));
        }
        for (ModuleConfig config : store.findAll()) {
            infos.putIfAbsent(config.name(), new ModuleInfoBuilder(config.name())
                    .version("unknown")
                    .enabled(config.enabled())
                    // Konfiguriert, aber keine Implementierung auf dem Classpath.
                    .state(ModuleState.DISABLED)
                    .build());
        }
        return List.copyOf(infos.values());
    }

    @Override
    public Optional<ModuleInfo> get(String name) {
        requireName(name);
        Holder holder = holders.get(name);
        if (holder != null) {
            return Optional.of(info(holder));
        }
        return store.find(name).map(config -> new ModuleInfoBuilder(config.name())
                .version("unknown")
                .enabled(config.enabled())
                .state(ModuleState.DISABLED)
                .build());
    }

    @Override
    public boolean isEnabled(String name) {
        requireName(name);
        Holder holder = holders.get(name);
        if (holder != null) {
            return holder.state == ModuleState.ENABLED;
        }
        return store.find(name).map(ModuleConfig::enabled).orElse(false);
    }

    @Override
    public Map<String, String> settings(String name) {
        String module = requireName(name);
        return store.find(module).map(ModuleConfig::settings).orElseGet(Map::of);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Die Rechte berechnet das Modul aus seinen Einstellungen: Es kennt seine
     * Schlüsselnamen und weiß, was eine fehlende Angabe bedeutet. Liegt das Modul
     * nicht in {@code lib/}, bleibt die Antwort leer — aus der Konfiguration allein
     * lässt sich nicht ableiten, was ein fremdes Modul daraus liest.
     */
    @Override
    public Collection<AccessGrant> access(String name) {
        String module = requireName(name);
        Holder holder = holders.get(module);
        return holder == null ? List.of() : holder.module.access(settings(module));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Ein Modul, das gerade nicht läuft, verwirft seinen Ladezustand: Der
     * nächste {@code enable} ruft {@code onLoad} erneut auf und liest damit die
     * neuen Einstellungen. Ein laufendes Modul bleibt unangetastet — es hat seine
     * Einstellungen schon gelesen, der neue Wert wirkt erst nach einem Neustart
     * der Cloud.
     */
    @Override
    public void setSetting(String name, String key, String value) {
        String module = requireName(name);
        String setting = requireSetting(key);
        String stored = requireValue(setting, value);
        Holder holder = holders.get(module);
        if (holder == null && store.find(module).isEmpty()) {
            throw new IllegalArgumentException("Unbekanntes Modul: " + module);
        }

        Map<String, String> settings = new LinkedHashMap<>(settings(module));
        settings.put(setting, stored);
        store.save(new ModuleConfig(module, isEnabled(module), settings));
        if (holder != null) {
            synchronized (holder) {
                if (holder.state != ModuleState.ENABLED) {
                    holder.loaded = false;
                }
            }
        }
        log.info("Einstellung '{}' von Modul '{}' gespeichert", setting, module);
    }

    @Override
    public CompletableFuture<Void> enable(String name) {
        String module = requireName(name);
        return CompletableFuture.runAsync(() -> load(module), lifecycle);
    }

    @Override
    public CompletableFuture<Void> disable(String name) {
        String module = requireName(name);
        return CompletableFuture.runAsync(() -> unload(module), lifecycle);
    }

    @Override
    public void close() {
        lifecycle.shutdownNow();
    }

    /**
     * Setzt den Schaltzustand in MongoDB.
     *
     * @param name    Modulname
     * @param enabled neuer Zustand
     */
    private void persist(String name, boolean enabled) {
        Map<String, String> settings = store.find(name).map(ModuleConfig::settings).orElseGet(Map::of);
        store.save(new ModuleConfig(name, enabled, settings));
    }

    private void load(String name) {
        Holder holder = holders.get(name);
        if (holder == null) {
            throw new IllegalStateException("Kein Modul '" + name + "' im Classpath (lib/) gefunden");
        }
        synchronized (holder) {
            if (holder.state == ModuleState.ENABLED) {
                return;
            }
            try {
                if (!holder.loaded) {
                    holder.module.onLoad(holder.context);
                    holder.loaded = true;
                }
                holder.module.onEnable();
                holder.state = ModuleState.ENABLED;
                persist(name, true);
                log.info("Modul '{}' aktiviert", name);
                publish(name, ModuleState.ENABLED);
            } catch (RuntimeException e) {
                holder.state = ModuleState.FAILED;
                log.error("Modul '{}' konnte nicht aktiviert werden: {}", name, messageOf(e));
                publish(name, ModuleState.FAILED);
                throw e;
            }
        }
    }

    private void unload(String name) {
        Holder holder = holders.get(name);
        if (holder == null) {
            // Auch ohne Implementierung bleibt der Schaltzustand setzbar.
            if (store.find(name).isPresent()) {
                persist(name, false);
                log.info("Modul '{}' deaktiviert (keine Implementierung geladen)", name);
                publish(name, ModuleState.DISABLED);
                return;
            }
            throw new IllegalStateException("Kein Modul '" + name + "' im Classpath (lib/) gefunden");
        }
        synchronized (holder) {
            try {
                if (holder.loaded && holder.state == ModuleState.ENABLED) {
                    holder.module.onDisable();
                }
                holder.state = ModuleState.DISABLED;
                persist(name, false);
                log.info("Modul '{}' deaktiviert", name);
                publish(name, ModuleState.DISABLED);
            } catch (RuntimeException e) {
                holder.state = ModuleState.FAILED;
                log.error("Modul '{}' konnte nicht gestoppt werden: {}", name, messageOf(e));
                publish(name, ModuleState.FAILED);
                throw e;
            }
        }
    }

    /**
     * Veröffentlicht einen Zustandswechsel. Die Meldung ist eine
     * Benachrichtigung: Ist der Publisher nicht erreichbar, bleibt der
     * Schaltvorgang trotzdem gültig und nur eine Warnung stehen.
     *
     * @param name  Modulname
     * @param state neuer Zustand
     */
    private void publish(String name, ModuleState state) {
        try {
            events.accept(ModuleStateChanged.newBuilder().setName(name).setState(state).build());
        } catch (RuntimeException e) {
            log.warn("Zustand von Modul '{}' konnte nicht veröffentlicht werden: {}",
                    name, messageOf(e));
        }
    }

    private void register(CloudModule module) {
        String name = requireName(module.name().toLowerCase(Locale.ROOT));
        Holder holder = new Holder(module, new Context(name));
        if (holders.putIfAbsent(name, holder) != null) {
            throw new IllegalStateException("Zwei Module mit dem Namen '" + name + "' gefunden");
        }
        log.debug("Modul '{}' {} gefunden", name, module.version());
    }

    private ModuleInfo info(Holder holder) {
        return new ModuleInfoBuilder(holder.name)
                .version(holder.module.version())
                .enabled(holder.state == ModuleState.ENABLED)
                .state(holder.state)
                .build();
    }

    private static List<CloudModule> discover() {
        List<CloudModule> modules = new ArrayList<>();
        for (CloudModule module : ServiceLoader.load(CloudModule.class)) {
            modules.add(module);
        }
        return modules;
    }

    /**
     * @param key Einstellungsname, z. B. {@code apiKey}
     * @return der geprüfte Name
     * @throws IllegalArgumentException wenn der Name leer oder unzulässig ist
     */
    private static String requireSetting(String key) {
        Objects.requireNonNull(key, "key");
        String trimmed = key.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Einstellungsname darf nicht leer sein");
        }
        if (!trimmed.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Ungültiger Einstellungsname: " + key);
        }
        return trimmed;
    }

    /**
     * @param key   Einstellungsname
     * @param value Wert
     * @return der geprüfte Wert
     * @throws IllegalArgumentException wenn der Wert leer, zu lang oder mehrzeilig ist
     */
    private static String requireValue(String key, String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Wert für '" + key + "' darf nicht leer sein");
        }
        if (trimmed.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("Wert für '" + key + "' ist länger als "
                    + MAX_VALUE_LENGTH + " Zeichen");
        }
        return trimmed;
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        String trimmed = name.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Modulname darf nicht leer sein");
        }
        if (!trimmed.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Ungültiger Modulname: " + name);
        }
        return trimmed;
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /** Zustand eines gefundenen Moduls. */
    private static final class Holder {

        private final String name;
        private final CloudModule module;
        private final ModuleContext context;
        private ModuleState state = ModuleState.DISABLED;
        private boolean loaded;

        private Holder(CloudModule module, ModuleContext context) {
            this.name = module.name().toLowerCase(Locale.ROOT);
            this.module = module;
            this.context = context;
            this.state = ModuleState.LOADED;
        }
    }

    /** Kontext, den ein Modul beim Laden erhält (§16). */
    private final class Context implements ModuleContext {

        private final String name;
        private final Logger logger;

        private Context(String name) {
            this.name = name;
            this.logger = LoggerFactory.getLogger("bytestorm.module." + name);
        }

        @Override
        public ByteStormApi api() {
            return api.get();
        }

        @Override
        public Path dataDirectory() {
            return modulesDirectory.resolve(name);
        }

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public Map<String, String> settings() {
            return store.find(name).map(ModuleConfig::settings).orElseGet(Map::of);
        }
    }

    /** Kleiner Builder, damit die Aufrufe in {@link #list()} lesbar bleiben. */
    private static final class ModuleInfoBuilder {

        private final ModuleInfo.Builder builder;

        private ModuleInfoBuilder(String name) {
            this.builder = ModuleInfo.newBuilder().setName(name);
        }

        private ModuleInfoBuilder version(String version) {
            builder.setVersion(version == null ? "unknown" : version);
            return this;
        }

        private ModuleInfoBuilder enabled(boolean enabled) {
            builder.setEnabled(enabled);
            return this;
        }

        private ModuleInfoBuilder state(ModuleState state) {
            builder.setState(state);
            return this;
        }

        private ModuleInfo build() {
            return builder.build();
        }
    }
}
