package dev.bytestorm.launcher.wizard;

import dev.bytestorm.core.config.CloudConfig;
import dev.bytestorm.core.config.ConfigException;
import dev.bytestorm.core.config.ConfigLoader;
import dev.bytestorm.core.config.ConfigWriter;
import dev.bytestorm.core.config.CrashSettings;
import dev.bytestorm.core.config.DockerSettings;
import dev.bytestorm.core.config.ModuleSettings;
import dev.bytestorm.core.config.MongoSettings;
import dev.bytestorm.core.config.PortSettings;
import dev.bytestorm.core.config.ProxySettings;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.core.config.ScalingSettings;
import dev.bytestorm.core.mongo.model.ModuleConfig;
import dev.bytestorm.proto.RuntimeType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Erststart-Wizard (§5.1).
 *
 * <p>Fragt MongoDB, Redis, Runtime, Ports und optionale Module ab, prüft beide
 * Verbindungen vor dem Speichern und wiederholt die Eingabe bei Fehlern, statt
 * abzubrechen. Die Konfiguration wird atomar und mit Rechten {@code 0600}
 * geschrieben, weil sie Zugangsdaten enthält (§20). Erst danach werden die
 * Standardgruppen angelegt und die aktivierten Module samt ihrer Einstellungen in
 * der Modul-Konfiguration hinterlegt — ein aktiviertes REST-Modul bekommt hier
 * seinen API-Key, damit die Cloud es ohne Handarbeit starten kann (§11, §16).
 *
 * <p>Ein Abbruch (Strg+C, EOF oder abgelehnter Wiederholungsversuch) hinterlässt
 * weder eine halbe Konfiguration noch eine temporäre Datei.
 */
public final class FirstRunWizard {

    /** Relative Lage der Konfiguration im Cloud-Verzeichnis. */
    public static final String CONFIG_FILE = "config/cloud.json";

    /** Erlaubte Werte für die Runtime-Abfrage. */
    public static final List<String> RUNTIMES = List.of("PROCESS", "DOCKER");

    private final WizardIo io;
    private final ConnectionTester tester;
    private final Consumer<MongoSettings> defaultGroups;
    private final BiConsumer<MongoSettings, List<ModuleConfig>> moduleSetup;

    /**
     * @param io            Ein-/Ausgabe
     * @param tester        Prüfung der Verbindungen
     * @param defaultGroups legt nach dem Speichern die Standardgruppen an
     */
    public FirstRunWizard(WizardIo io, ConnectionTester tester, Consumer<MongoSettings> defaultGroups) {
        this(io, tester, defaultGroups, (settings, modules) -> {
        });
    }

    /**
     * @param io            Ein-/Ausgabe
     * @param tester        Prüfung der Verbindungen
     * @param defaultGroups legt nach dem Speichern die Standardgruppen an
     * @param moduleSetup   speichert die Konfiguration der aktivierten Module
     */
    public FirstRunWizard(WizardIo io, ConnectionTester tester, Consumer<MongoSettings> defaultGroups,
                          BiConsumer<MongoSettings, List<ModuleConfig>> moduleSetup) {
        this.io = Objects.requireNonNull(io, "io");
        this.tester = Objects.requireNonNull(tester, "tester");
        this.defaultGroups = Objects.requireNonNull(defaultGroups, "defaultGroups");
        this.moduleSetup = Objects.requireNonNull(moduleSetup, "moduleSetup");
    }

    /**
     * Führt den Wizard aus und schreibt {@code config/cloud.json}.
     *
     * @param cloudDirectory Cloud-Verzeichnis
     * @return die geschriebene Konfiguration
     * @throws IOException            wenn die Konfiguration nicht geschrieben werden kann
     * @throws WizardAbortedException wenn die Einrichtung abgebrochen wird
     */
    public CloudConfig run(Path cloudDirectory) throws IOException {
        Objects.requireNonNull(cloudDirectory, "cloudDirectory");
        Path configFile = cloudDirectory.resolve(CONFIG_FILE);

        MongoSettings mongo = askMongo();
        RedisSettings redis = askRedis();
        RuntimeType runtime = askRuntime();
        String dockerHost = runtime == RuntimeType.DOCKER
                ? io.ask("Docker-Host", DockerSettings.DEFAULT_HOST)
                : DockerSettings.DEFAULT_HOST;
        int proxyPort = askPort("Proxy-Port", PortSettings.DEFAULT_PROXY);
        int serverPort = askPort("Server-Startport", PortSettings.DEFAULT_SERVER_START);
        boolean rest = io.confirm("REST-Modul aktivieren?", false);
        // Nur fragen, was das Modul zum Starten braucht (§11): Adresse, Port und Key.
        String restHost = rest ? io.ask("REST-Host", DefaultModules.REST_DEFAULT_HOST) : "";
        int restPort = rest ? askPort("REST-Port", DefaultModules.REST_DEFAULT_PORT) : 0;
        String restKey = rest ? askRestKey() : "";
        boolean discord = io.confirm("Discord-Modul aktivieren?", false);
        List<ModuleConfig> modules = moduleConfigs(rest, restHost, restPort, restKey, discord);

        CloudConfig config = new CloudConfig(
                mongo,
                redis,
                runtime,
                new DockerSettings(dockerHost),
                new PortSettings(proxyPort, serverPort),
                // Einmal erzeugt und nie neu gewürfelt: laufende Proxies bleiben sonst abgekoppelt.
                new ProxySettings(ProxySettings.generateSecret()),
                new ModuleSettings(rest, discord),
                new ScalingSettings(0),
                new CrashSettings(0, null));

        try {
            new ConfigLoader().validate(config);
        } catch (ConfigException e) {
            throw new WizardAbortedException("Ungültige Eingaben: " + e.getMessage());
        }

        ConfigWriter.write(configFile, config);
        io.message("Konfiguration gespeichert: " + configFile);

        try {
            defaultGroups.accept(mongo);
            io.message("Standardgruppen 'Proxy' und 'Lobby' sind angelegt (falls noch nicht vorhanden).");
        } catch (RuntimeException e) {
            io.message("Hinweis: Standardgruppen konnten nicht angelegt werden: " + describe(e));
        }
        if (!modules.isEmpty()) {
            try {
                moduleSetup.accept(mongo, modules);
                io.message("Modul-Konfiguration gespeichert: " + String.join(", ",
                        modules.stream().map(ModuleConfig::name).toList())
                        + (rest ? " — das REST-Modul startet auf " + restHost + ":" + restPort : ""));
            } catch (RuntimeException e) {
                io.message("Hinweis: Modul-Konfiguration konnte nicht gespeichert werden: " + describe(e));
            }
        }
        return config;
    }

    /**
     * Sammelt die Konfiguration der aktivierten Module. Nur aktivierte Module
     * bekommen einen Eintrag; der API-Key geht ausschließlich nach MongoDB, nie in
     * die Antwort oder in eine Meldung (§20).
     *
     * @param rest     REST-Modul aktiv
     * @param restHost Netzadresse des REST-Moduls
     * @param restPort Port des REST-Moduls
     * @param restKey  API-Key des REST-Moduls
     * @param discord  Discord-Modul aktiv
     * @return Konfigurationen für {@code modules_config}
     */
    private static List<ModuleConfig> moduleConfigs(boolean rest, String restHost, int restPort,
                                                    String restKey, boolean discord) {
        List<ModuleConfig> modules = new ArrayList<>(2);
        if (rest) {
            modules.add(new ModuleConfig(DefaultModules.REST, true, Map.of(
                    DefaultModules.REST_HOST, restHost,
                    DefaultModules.REST_PORT, String.valueOf(restPort),
                    DefaultModules.REST_API_KEY, restKey)));
        }
        if (discord) {
            modules.add(new ModuleConfig("discord", true, Map.of()));
        }
        return List.copyOf(modules);
    }

    /**
     * Fragt den API-Key ab und wiederholt, solange er zu kurz ist — sonst
     * verweigert das REST-Modul beim Start den Dienst.
     *
     * @return der geprüfte Key
     */
    private String askRestKey() {
        while (true) {
            String key = io.askSecret("REST-API-Key (mindestens "
                    + DefaultModules.REST_MIN_KEY_LENGTH + " Zeichen)");
            if (key != null && key.trim().length() >= DefaultModules.REST_MIN_KEY_LENGTH) {
                return key.trim();
            }
            io.message("Der API-Key braucht mindestens " + DefaultModules.REST_MIN_KEY_LENGTH
                    + " Zeichen, sonst verweigert das REST-Modul den Start.");
        }
    }

    private MongoSettings askMongo() {
        while (true) {
            String uri = io.ask("MongoDB-URI", MongoSettings.DEFAULT_URI);
            String username = io.ask("MongoDB-Benutzer (optional)", "");
            String password = null;
            if (!username.isBlank()) {
                password = io.askSecret("MongoDB-Passwort");
                if (password == null || password.isBlank()) {
                    io.message("MongoDB-Passwort ist Pflicht, wenn ein Benutzer angegeben ist.");
                    continue;
                }
            }
            MongoSettings settings = new MongoSettings(
                    MongoUris.withCredentials(uri, username, password),
                    io.ask("MongoDB-Datenbank", MongoSettings.DEFAULT_DATABASE));
            try {
                tester.testMongo(settings);
                return settings;
            } catch (RuntimeException e) {
                io.message("MongoDB nicht erreichbar: " + describe(e));
                retry("MongoDB-Zugang erneut eingeben?");
            }
        }
    }

    private RedisSettings askRedis() {
        while (true) {
            String address = io.ask("Redis-Adresse", RedisSettings.DEFAULT_ADDRESS);
            String password = io.askSecret("Redis-Passwort");
            if (password == null || password.isBlank()) {
                io.message("Redis-Passwort ist Pflicht (§20).");
                continue;
            }
            RedisSettings settings = new RedisSettings(address, password);
            try {
                tester.testRedis(settings);
                return settings;
            } catch (RuntimeException e) {
                io.message("Redis nicht erreichbar oder Passwort falsch: " + describe(e));
                retry("Redis-Zugang erneut eingeben?");
            }
        }
    }

    private RuntimeType askRuntime() {
        String choice = io.choose("Runtime", RUNTIMES, RuntimeType.PROCESS.name());
        return RuntimeType.valueOf(choice.toUpperCase(Locale.ROOT));
    }

    private int askPort(String label, int defaultValue) {
        while (true) {
            String raw = io.ask(label, String.valueOf(defaultValue));
            try {
                int port = Integer.parseInt(raw.trim());
                if (port >= 1 && port <= 65535) {
                    return port;
                }
            } catch (NumberFormatException ignored) {
                // unten erneut fragen
            }
            io.message("Bitte eine Portnummer zwischen 1 und 65535 eingeben.");
        }
    }

    private void retry(String prompt) {
        if (!io.confirm(prompt, true)) {
            throw new WizardAbortedException("Einrichtung auf Wunsch abgebrochen");
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
