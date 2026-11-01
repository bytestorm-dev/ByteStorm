package dev.bytestorm.plugins.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.bytestorm.plugins.proxy.cloud.CloudClient;
import dev.bytestorm.plugins.proxy.cloud.HttpCloudClient;
import dev.bytestorm.plugins.proxy.command.CloudCommand;
import dev.bytestorm.plugins.proxy.command.CloudPermissions;
import dev.bytestorm.plugins.proxy.command.CloudCommandRouter;
import dev.bytestorm.plugins.proxy.command.PluginControl;
import dev.bytestorm.plugins.proxy.command.Reply;
import dev.bytestorm.plugins.proxy.config.ProxySettings;
import dev.bytestorm.plugins.proxy.config.SettingsEditor;
import dev.bytestorm.plugins.proxy.config.SettingsLoader;
import dev.bytestorm.plugins.proxy.maintenance.MaintenanceGate;
import dev.bytestorm.plugins.proxy.message.Messages;
import dev.bytestorm.plugins.proxy.text.Components;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Proxy-Plugin der Cloud (§16): Tablist, Wartungsmodus und die Cloud-Kommandos,
 * vollständig über JSON übersetzbar.
 *
 * <p>Das Plugin läuft ohne Cloud weiter — es liest weder MongoDB noch Redis. Der
 * Betreiber legt es in den {@code plugins/}-Ordner seines Proxys; beim ersten
 * Start entstehen {@code config.json} und {@code messages/} im Datenverzeichnis.
 *
 * <p>Die Datei bestimmt nur, <em>was</em> läuft ({@code tablist}, {@code maintenance},
 * {@code cloud}, Sprache). Alle Texte stehen in {@code messages/<sprache>.json},
 * sind MiniMessage und nutzen Platzhalter wie {@code %players%}.
 * Änderungen an {@code config.json} werden im Sekundentakt übernommen; ist die
 * neue Datei fehlerhaft, bleiben die alten Einstellungen aktiv.
 *
 * <p>{@code /cloud} (Aliasse {@code /c}, {@code /bs}, {@code /bytestorm}) ist ein
 * dünner Client des REST-Moduls; es ersetzt die CLI, ohne deren interaktive
 * Ansichten ({@code watch}, {@code screen}).
 */
@Plugin(id = "bytestorm-proxy", name = "ByteStormProxy", version = "1.0-SNAPSHOT",
        description = "Tablist, Wartungsmodus und Cloud-Kommandos, vollständig über JSON übersetzbar.",
        authors = {"ByteStorm"})
public final class ProxyPlugin implements PluginControl {

    /** Konfigurationsdatei im Datenverzeichnis. */
    public static final String CONFIG_FILE = "config.json";

    /** Unterordner mit betreiberspezifischen Übersetzungen. */
    public static final String MESSAGES_DIRECTORY = "messages";

    /** Schlüssel der Wartungsnachricht beim Login. */
    public static final String MAINTENANCE_MESSAGE = "server-join-cancel-because-maintenance";

    private static final String HEADER_KEY = "tablist.header";
    private static final String FOOTER_KEY = "tablist.footer";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private volatile Loaded loaded;
    private FileTime configModified = FileTime.fromMillis(0);
    private long lastTablistUpdate;
    private ScheduledExecutorService ticker;
    private CloudCommandRouter commands;

    /**
     * @param server        Proxy
     * @param logger        Plugin-Logger
     * @param dataDirectory Datenverzeichnis des Plugins
     */
    @Inject
    public ProxyPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    /**
     * Legt Konfiguration und Sprachordner an, lädt beides, meldet {@code /cloud}
     * an und startet die Aktualisierung.
     *
     * @param event Proxy-Start
     */
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // Auch ohne gültige Konfiguration erreichbar: Dann antwortet /cloud mit
        // einem Hinweis statt mit „unbekanntes Kommando".
        commands = new CloudCommandRouter(logger, this::commandLines, source -> CloudCommand.suggestions());
        commands.register(server, this);

        if (!start()) {
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bytestorm-proxy");
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Beendet die Aktualisierung und meldet {@code /cloud} ab.
     *
     * @param event Proxy-Stop
     */
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        ScheduledExecutorService running = ticker;
        ticker = null;
        if (running != null) {
            running.shutdownNow();
        }
        CloudCommandRouter registered = commands;
        commands = null;
        if (registered != null) {
            registered.unregister(server);
            registered.close();
        }
        loaded = null;
    }

    /**
     * Weist Logins während der Wartung ab, sofern keine Bypass-Permission und
     * keine Ausnahme greift.
     *
     * @param event Login
     */
    @Subscribe
    public void onLogin(LoginEvent event) {
        Loaded current = loaded;
        if (current == null) {
            return;
        }
        Player player = event.getPlayer();
        boolean bypass = player.hasPermission(current.config().maintenance().bypassPermission());
        if (!current.gate().denies(player.getUsername(), bypass)) {
            return;
        }
        Component reason = Components.of(current.messages()
                .text(MAINTENANCE_MESSAGE, Map.of("player", player.getUsername())));
        event.setResult(ResultedEvent.ComponentResult.denied(reason));
        logger.info("Login von '{}' während der Wartung abgelehnt", player.getUsername());
    }

    /**
     * Schreibt die Tablist direkt nach dem Login, damit neue Spieler nicht bis zum
     * nächsten Intervall auf eine leere Tablist sehen.
     *
     * @param event erfolgreicher Login
     */
    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Loaded current = loaded;
        if (current == null || !current.config().tablist().enabled()) {
            return;
        }
        updateTablist(current, List.of(event.getPlayer()));
    }

    /**
     * @return {@code true}, solange Konfiguration und Sprachdateien geladen sind
     */
    public boolean isRunning() {
        return loaded != null;
    }

    /**
     * Führt {@code /cloud} aus. Die Permission und die Texte stammen aus der
     * aktuellen Konfiguration, damit ein Reload auch für das Kommando gilt.
     *
     * @param line   Eingabe ohne Kommandonamen
     * @param source Absender
     * @return Antwortzeilen
     */
    private List<Reply> commandLines(String line, CommandSource source) {
        Loaded current = loaded;
        if (current == null) {
            Messages fallback = fallbackMessages();
            return List.of(Reply.of(fallback == null
                    ? "/cloud is not available: the plugin is not running"
                    : fallback.text("cloud.notRunning", Map.of())));
        }
        // cloud.permission ist der Admin-Override und erlaubt jedes Kommando;
        // alles andere läuft über bytestorm.command.<kommando>. tablist.permission
        // bleibt als zusätzliche Node für den Tablist-Schalter erhalten.
        boolean override = source.hasPermission(current.config().cloud().permission());
        boolean tablistExtra = source.hasPermission(current.config().tablist().permission());
        String tablistNode = CloudPermissions.node("tablist");
        CloudPermissions permissions = new CloudPermissions(node -> override
                || source.hasPermission(node)
                || (tablistExtra && node.equals(tablistNode)));
        try {
            return current.command().execute(line, permissions, current.messages());
        } catch (RuntimeException e) {
            return List.of(Reply.of(
                    current.messages().text("cloud.error", Map.of("reason", reasonOf(e)))));
        }
    }

    private Messages fallbackMessages() {
        try {
            return Messages.load(ProxySettings.DEFAULT_LANGUAGE, messagesDirectory());
        } catch (IOException e) {
            return null;
        }
    }

    private static String reasonOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    @Override
    public boolean tablistEnabled() {
        Loaded current = loaded;
        return current != null && current.config().tablist().enabled();
    }

    /**
     * Schreibt den Schalter in {@code config.json} und übernimmt die Datei sofort,
     * damit die Tablist nicht erst beim nächsten Tick reagiert.
     *
     * @param enabled neuer Zustand
     */
    @Override
    public void tablistEnabled(boolean enabled) {
        try {
            new SettingsEditor().setTablistEnabled(configFile(), enabled);
            apply(new SettingsLoader().load(configFile()));
        } catch (IOException e) {
            String message = e.getMessage();
            throw new IllegalStateException(message == null || message.isBlank()
                    ? "config.json konnte nicht geschrieben werden" : message, e);
        }
    }

    @Override
    public String language() {
        Loaded current = loaded;
        return current == null ? null : current.config().language();
    }

    @Override
    public String effectiveLanguage() {
        Loaded current = loaded;
        return current == null ? null : current.messages().language();
    }

    private boolean start() {
        try {
            Files.createDirectories(dataDirectory);
            Files.createDirectories(messagesDirectory());
            if (!Files.isRegularFile(configFile())) {
                writeDefaultConfig();
            }
            apply(new SettingsLoader().load(configFile()));
            return true;
        } catch (IOException | RuntimeException e) {
            logger.error("ByteStormProxy nicht gestartet: {}", e.getMessage());
            return false;
        }
    }

    private void apply(ProxySettings config) throws IOException {
        Messages messages = Messages.load(config.language(), messagesDirectory());
        CloudClient cloud = new HttpCloudClient(config.cloud().baseUrl(), config.cloud().apiKey(),
                Duration.ofSeconds(config.cloud().timeoutSeconds()));
        loaded = new Loaded(config, messages, new MaintenanceGate(config.maintenance()),
                new CloudCommand(cloud, config.cloud().usable(), this));
        configModified = modifiedAt(configFile());
        logger.info("ByteStormProxy bereit (Sprache {}, Tablist {}, Wartung {}, /cloud {})",
                messages.language(),
                config.tablist().enabled() ? "an" : "aus",
                config.maintenance().enabled() ? "an" : "aus",
                config.cloud().usable() ? "an" : "aus");
        if (!messages.language().equals(config.language())) {
            logger.warn("Sprachdatei für '{}' fehlt — es gilt '{}'",
                    config.language(), messages.language());
        }
        if (config.cloud().enabled() && !config.cloud().usable()) {
            logger.warn("cloud.enabled ist gesetzt, aber cloud.apiKey fehlt — /cloud bleibt aus");
        }
    }

    /**
     * Ein Tick übernimmt eine geänderte {@code config.json} und schreibt die
     * Tablist, wenn ihr Intervall erreicht ist.
     */
    private void tick() {
        try {
            reloadIfChanged();
            Loaded current = loaded;
            if (current == null || !current.config().tablist().enabled()) {
                return;
            }
            long interval = current.config().tablist().updateSeconds() * 1_000L;
            long now = System.currentTimeMillis();
            if (now - lastTablistUpdate < interval) {
                return;
            }
            lastTablistUpdate = now;
            updateTablist(current, server.getAllPlayers());
        } catch (RuntimeException e) {
            logger.warn("Tablist-Aktualisierung fehlgeschlagen: {}", e.toString());
        }
    }

    private void reloadIfChanged() {
        FileTime modified;
        try {
            modified = modifiedAt(configFile());
        } catch (IOException e) {
            return;
        }
        if (modified.equals(configModified)) {
            return;
        }
        try {
            apply(new SettingsLoader().load(configFile()));
        } catch (IOException | RuntimeException e) {
            // Alte Einstellungen bleiben: Eine halb getippte Datei darf die Tablist
            // nicht ausschalten, die Wartung nicht beenden und /cloud nicht kappen.
            configModified = modified;
            logger.warn("config.json nicht übernommen, alte Einstellungen bleiben: {}", e.getMessage());
        }
    }

    private void updateTablist(Loaded current, Collection<Player> players) {
        Map<String, String> values = Map.of(
                "players", String.valueOf(server.getPlayerCount()),
                "max", String.valueOf(server.getConfiguration().getShowMaxPlayers()));
        Component header = Components.of(current.messages().find(HEADER_KEY, values).orElse(""));
        Component footer = Components.of(current.messages().find(FOOTER_KEY, values).orElse(""));
        for (Player player : players) {
            player.sendPlayerListHeaderAndFooter(header, footer);
        }
    }

    private void writeDefaultConfig() throws IOException {
        try (InputStream stream = ProxyPlugin.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (stream == null) {
                throw new IOException("Standard-Konfiguration fehlt im Plugin-Jar: " + CONFIG_FILE);
            }
            Files.copy(stream, configFile(), StandardCopyOption.REPLACE_EXISTING);
            logger.info("Standard-Konfiguration nach {} geschrieben", configFile());
        }
    }

    private Path configFile() {
        return dataDirectory.resolve(CONFIG_FILE);
    }

    private Path messagesDirectory() {
        return dataDirectory.resolve(MESSAGES_DIRECTORY);
    }

    private static FileTime modifiedAt(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.getLastModifiedTime(file) : FileTime.fromMillis(0);
    }

    /**
     * Zustand, den ein Tick benutzt: Konfiguration, Texte, Wartungsregel und
     * {@code /cloud} gehören zusammen und werden gemeinsam getauscht.
     */
    private record Loaded(ProxySettings config, Messages messages, MaintenanceGate gate,
                          CloudCommand command) {
    }
}
