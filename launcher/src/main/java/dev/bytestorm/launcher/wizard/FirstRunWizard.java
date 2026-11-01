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
import dev.bytestorm.core.config.SecuritySettings;
import dev.bytestorm.core.config.ScalingSettings;
import dev.bytestorm.core.mongo.model.ModuleConfig;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceGroup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Erststart-Wizard (§5.1).
 *
 * <p>Der Wizard stellt seine Fragen als ein Formular: eine Übersicht mit
 * aufklappbaren Abschnitten, in der die Tastatur alles erledigt — navigieren,
 * auswählen, tippen, abschicken. Die Antworten bleiben beim Wechsel der Frage
 * erhalten, und eine zugeklappte Frage zeigt ihre Antwort als Kurzfassung.
 *
 * <p>Abgeschickt wird erst, wenn alle Felder gültig sind. Danach prüft der Wizard
 * MongoDB und Redis; schlägt eine Verbindung fehl, öffnet er dasselbe Formular
 * erneut, springt auf die betroffene Frage und zeigt den Fehler darunter. Ein
 * Abbruch (Strg+C, EOF oder bestätigtes Esc) hinterlässt weder eine halbe
 * Konfiguration noch eine temporäre Datei.
 *
 * <p>Erst nach erfolgreicher Prüfung wird die Konfiguration atomar und mit
 * Rechten {@code 0600} geschrieben, weil sie Zugangsdaten enthält (§20). Danach
 * entstehen die zuvor abgefragten Gruppen ({@link GroupSetup}) und die aktivierten
 * Module samt Einstellungen — ein aktiviertes REST-Modul bekommt hier seinen
 * API-Key (§11, §16).
 */
public final class FirstRunWizard {

    /** Relative Lage der Konfiguration im Cloud-Verzeichnis. */
    public static final String CONFIG_FILE = "config/cloud.json";

    /** Erlaubte Werte für die Runtime-Abfrage. */
    public static final List<String> RUNTIMES = List.of("PROCESS", "DOCKER");

    /** ID der MongoDB-URI. */
    public static final String MONGO_URI = "mongo.uri";
    /** ID des MongoDB-Benutzers. */
    public static final String MONGO_USER = "mongo.user";
    /** ID des MongoDB-Passworts. */
    public static final String MONGO_PASSWORD = "mongo.password";
    /** ID der MongoDB-Datenbank. */
    public static final String MONGO_DATABASE = "mongo.database";
    /** ID der Redis-Adresse. */
    public static final String REDIS_ADDRESS = "redis.address";
    /** ID des Redis-Passworts. */
    public static final String REDIS_PASSWORD = "redis.password";
    /** ID der Runtime. */
    public static final String RUNTIME = "runtime";
    /** ID des Docker-Hosts. */
    public static final String DOCKER_HOST = "docker.host";
    /** ID des Proxy-Ports. */
    public static final String PROXY_PORT = "ports.proxy";
    /** ID des Server-Startports. */
    public static final String SERVER_PORT = "ports.serverStart";
    /** ID der REST-Freigabe. */
    public static final String REST = "modules.rest";
    /** ID des REST-Hosts. */
    public static final String REST_HOST = "rest.host";
    /** ID des REST-Ports. */
    public static final String REST_PORT = "rest.port";
    /** ID des REST-API-Keys. */
    public static final String REST_API_KEY = "rest.apiKey";
    /** ID der Discord-Freigabe. */
    public static final String DISCORD = "modules.discord";

    private static final String PORT_MESSAGE = "Bitte eine Portnummer zwischen 1 und 65535 eingeben.";
    private static final String REST_KEY_MESSAGE =
            "Der API-Key braucht mindestens " + DefaultModules.REST_MIN_KEY_LENGTH
                    + " Zeichen, sonst verweigert das REST-Modul den Start.";

    private static final Question.Validator PORT = (value, answers) -> {
        try {
            int port = Integer.parseInt(value.trim());
            return port >= 1 && port <= 65535 ? null : PORT_MESSAGE;
        } catch (NumberFormatException e) {
            return PORT_MESSAGE;
        }
    };

    private final WizardIo io;
    private final ConnectionTester tester;
    private final BiFunction<MongoSettings, List<ServiceGroup>, List<String>> groupSetup;
    private final BiConsumer<MongoSettings, List<ModuleConfig>> moduleSetup;

    /**
     * @param io         Ein-/Ausgabe
     * @param tester     Prüfung der Verbindungen
     * @param groupSetup legt nach dem Speichern die abgefragten Gruppen an
     */
    public FirstRunWizard(WizardIo io, ConnectionTester tester,
                          BiFunction<MongoSettings, List<ServiceGroup>, List<String>> groupSetup) {
        this(io, tester, groupSetup, (settings, modules) -> {
        });
    }

    /**
     * @param io         Ein-/Ausgabe
     * @param tester     Prüfung der Verbindungen
     * @param groupSetup legt nach dem Speichern die abgefragten Gruppen an
     * @param moduleSetup speichert die Konfiguration der aktivierten Module
     */
    public FirstRunWizard(WizardIo io, ConnectionTester tester,
                          BiFunction<MongoSettings, List<ServiceGroup>, List<String>> groupSetup,
                          BiConsumer<MongoSettings, List<ModuleConfig>> moduleSetup) {
        this.io = Objects.requireNonNull(io, "io");
        this.tester = Objects.requireNonNull(tester, "tester");
        this.groupSetup = Objects.requireNonNull(groupSetup, "groupSetup");
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

        Questionnaire form = form();
        Answers answers = io.fill(form, Answers.empty(), QuestionnaireNotice.none());
        CloudConfig config = complete(form, answers);
        // Erst fragen, dann schreiben: Ein Abbruch bei den Gruppen hinterlässt keine Konfiguration.
        List<ServiceGroup> groups = GroupSetup.ask(io);

        ConfigWriter.write(configFile, config);
        io.message("Konfiguration gespeichert: " + configFile);

        try {
            List<String> created = groupSetup.apply(config.mongo(), groups);
            io.message(created.isEmpty()
                    ? "Gruppen unverändert (schon vorhanden): " + names(groups) + "."
                    : "Gruppen angelegt: " + String.join(", ", created) + ".");
        } catch (RuntimeException e) {
            io.message("Hinweis: Gruppen konnten nicht angelegt werden: " + describe(e));
        }
        List<ModuleConfig> modules = modules(form, answers, config);
        if (!modules.isEmpty()) {
            try {
                moduleSetup.accept(config.mongo(), modules);
                io.message("Modul-Konfiguration gespeichert: " + String.join(", ",
                        modules.stream().map(ModuleConfig::name).toList())
                        + (config.modules().rest()
                        ? " — das REST-Modul startet auf " + form.question(REST_HOST).effective(answers)
                        + ":" + form.question(REST_PORT).effective(answers)
                        : ""));
            } catch (RuntimeException e) {
                io.message("Hinweis: Modul-Konfiguration konnte nicht gespeichert werden: " + describe(e));
            }
        }
        return config;
    }

    /**
     * Schickt das Formular ab und prüft die Verbindungen. Schlägt eine Prüfung
     * fehl, öffnet sich das Formular erneut mit dem Fehler an der betroffenen
     * Frage; die bisherigen Antworten bleiben stehen.
     */
    private CloudConfig complete(Questionnaire form, Answers answers) {
        while (true) {
            CloudConfig config = config(form, answers);
            try {
                new ConfigLoader().validate(config);
            } catch (ConfigException e) {
                throw new WizardAbortedException("Ungültige Eingaben: " + e.getMessage());
            }
            QuestionnaireNotice notice = probe(config);
            if (notice == null) {
                return config;
            }
            answers = io.fill(form, answers, notice);
        }
    }

    /**
     * @return die Meldung der ersten fehlgeschlagenen Verbindung oder {@code null}
     */
    private QuestionnaireNotice probe(CloudConfig config) {
        try {
            tester.testMongo(config.mongo());
        } catch (RuntimeException e) {
            return new QuestionnaireNotice(MONGO_URI, "MongoDB nicht erreichbar: " + describe(e));
        }
        try {
            tester.testRedis(config.redis());
        } catch (RuntimeException e) {
            return new QuestionnaireNotice(REDIS_ADDRESS,
                    "Redis nicht erreichbar oder Passwort falsch: " + describe(e));
        }
        return null;
    }

    /**
     * @return das Formular des Erststarts
     */
    static Questionnaire form() {
        List<Question> questions = List.of(
                Question.text(MONGO_URI, "MongoDB-URI")
                        .description("Adresse der Datenbank, in der Gruppen, Templates und Module liegen.")
                        .defaultValue(MongoSettings.DEFAULT_URI)
                        .validate((value, answers) -> startsWithScheme(value, "mongodb://", "mongodb+srv://")
                                ? null
                                : "Die MongoDB-URI muss mit mongodb:// oder mongodb+srv:// beginnen.")
                        .build(),
                Question.text(MONGO_USER, "MongoDB-Benutzer")
                        .description("Pflicht: Die Cloud verbindet sich nur mit Anmeldung (§20).")
                        .required(true)
                        .validate((value, answers) -> value.isBlank()
                                ? "MongoDB-Benutzer ist Pflicht — ohne Anmeldung startet die Cloud nicht (§20)."
                                : null)
                        .build(),
                Question.secret(MONGO_PASSWORD, "MongoDB-Passwort")
                        .description("Wird zusammen mit dem Benutzer prozentkodiert in die URI gesetzt.")
                        // Ohne Benutzer braucht die URI kein Passwort; die Frage erscheint dann gar nicht.
                        .visibleWhen(answers -> !answers.text(MONGO_USER).isBlank())
                        .validate((value, answers) -> value.isBlank()
                                ? "MongoDB-Passwort ist Pflicht, wenn ein Benutzer angegeben ist."
                                : null)
                        .build(),
                Question.text(MONGO_DATABASE, "MongoDB-Datenbank")
                        .defaultValue(MongoSettings.DEFAULT_DATABASE)
                        .required(true)
                        .build(),
                Question.text(REDIS_ADDRESS, "Redis-Adresse")
                        .description("Cache, Registry und Event-Bus der Cloud.")
                        .defaultValue(RedisSettings.DEFAULT_ADDRESS)
                        .validate((value, answers) -> startsWithScheme(value, "redis://", "rediss://")
                                ? null
                                : "Die Redis-Adresse muss mit redis:// oder rediss:// beginnen.")
                        .build(),
                Question.secret(REDIS_PASSWORD, "Redis-Passwort")
                        .description("Pflicht: Die Cloud verbindet sich nur mit Authentifizierung (§20).")
                        .validate((value, answers) -> value.isBlank()
                                ? "Redis-Passwort ist Pflicht (§20)."
                                : null)
                        .build(),
                Question.select(RUNTIME, "Runtime")
                        .description("Bestimmt, wie jeder Service startet.")
                        .option(RuntimeType.PROCESS.name(), RuntimeType.PROCESS.name(),
                                "Eigener Java-Prozess auf diesem Host; nichts weiter nötig.")
                        .option(RuntimeType.DOCKER.name(), RuntimeType.DOCKER.name(),
                                "Container über den Docker-Host.")
                        .defaultValue(RuntimeType.PROCESS.name())
                        .build(),
                Question.text(DOCKER_HOST, "Docker-Host")
                        .description("Socket oder TCP-Adresse des Docker-Daemons.")
                        .defaultValue(DockerSettings.DEFAULT_HOST)
                        .visibleWhen(answers -> RuntimeType.DOCKER.name().equals(answers.text(RUNTIME)))
                        .build(),
                Question.text(PROXY_PORT, "Proxy-Port")
                        .description("Port, auf dem Spieler die Cloud erreichen.")
                        .defaultValue(String.valueOf(PortSettings.DEFAULT_PROXY))
                        .validate(PORT)
                        .build(),
                Question.text(SERVER_PORT, "Server-Startport")
                        .description("Erster Port des Pools für Server-Services; aufwärts bis 65535.")
                        .defaultValue(String.valueOf(PortSettings.DEFAULT_SERVER_START))
                        .validate(PORT)
                        .build(),
                Question.confirm(REST, "REST-Modul aktivieren?")
                        .description("Stellt die öffentliche API über HTTP bereit.")
                        .build(),
                Question.text(REST_HOST, "REST-Host")
                        .description("Netzadresse, auf der das Modul lauscht.")
                        .defaultValue(DefaultModules.REST_DEFAULT_HOST)
                        .visibleWhen(FirstRunWizard::restEnabled)
                        .build(),
                Question.text(REST_PORT, "REST-Port")
                        .defaultValue(String.valueOf(DefaultModules.REST_DEFAULT_PORT))
                        .validate(PORT)
                        .visibleWhen(FirstRunWizard::restEnabled)
                        .build(),
                Question.secret(REST_API_KEY, "REST-API-Key")
                        .description("Mindestens " + DefaultModules.REST_MIN_KEY_LENGTH
                                + " Zeichen. Der Key geht ausschließlich in die Datenbank (§20).")
                        .visibleWhen(FirstRunWizard::restEnabled)
                        .validate((value, answers) -> value.trim().length() >= DefaultModules.REST_MIN_KEY_LENGTH
                                ? null
                                : REST_KEY_MESSAGE)
                        .build(),
                Question.confirm(DISCORD, "Discord-Modul aktivieren?")
                        .description("Meldet Status und Ereignisse an Discord.")
                        .build());
        return new Questionnaire("Ein paar Fragen zur Einrichtung",
                "Die Antworten landen in config/cloud.json.", questions);
    }

    private static boolean restEnabled(Answers answers) {
        return answers.flag(REST);
    }

    private static boolean startsWithScheme(String value, String... schemes) {
        String trimmed = value.trim();
        for (String scheme : schemes) {
            if (trimmed.startsWith(scheme)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Baut die Konfiguration aus den Antworten. Alle Werte sind an dieser Stelle
     * geprüft; leere Textfelder greifen auf ihre Vorschlagswerte zurück.
     */
    static CloudConfig config(Questionnaire form, Answers answers) {
        String user = value(form, answers, MONGO_USER).trim();
        String password = answers.text(MONGO_PASSWORD).isBlank() ? null : answers.text(MONGO_PASSWORD);
        MongoSettings mongo = new MongoSettings(
                MongoUris.withCredentials(value(form, answers, MONGO_URI), user, password),
                value(form, answers, MONGO_DATABASE));

        RuntimeType runtime = RuntimeType.valueOf(
                value(form, answers, RUNTIME).toUpperCase(Locale.ROOT));
        String dockerHost = runtime == RuntimeType.DOCKER
                ? value(form, answers, DOCKER_HOST)
                : DockerSettings.DEFAULT_HOST;

        boolean rest = answers.flag(REST);
        return new CloudConfig(
                mongo,
                new RedisSettings(value(form, answers, REDIS_ADDRESS), answers.text(REDIS_PASSWORD)),
                runtime,
                new DockerSettings(dockerHost),
                new PortSettings(port(form, answers, PROXY_PORT), port(form, answers, SERVER_PORT)),
                // Einmal erzeugt und nie neu gewürfelt: laufende Proxies bleiben sonst abgekoppelt.
                new ProxySettings(ProxySettings.generateSecret()),
                new ModuleSettings(rest, answers.flag(DISCORD)),
                new ScalingSettings(0),
                new CrashSettings(0, null),
                // Der Wizard liefert immer eine authentifizierte Konfiguration.
                SecuritySettings.defaults());
    }

    /**
     * Sammelt die Konfiguration der aktivierten Module. Nur aktivierte Module
     * bekommen einen Eintrag; der API-Key geht ausschließlich nach MongoDB, nie in
     * die Antwort oder in eine Meldung (§20).
     */
    private static List<ModuleConfig> modules(Questionnaire form, Answers answers, CloudConfig config) {
        List<ModuleConfig> modules = new ArrayList<>(2);
        if (config.modules().rest()) {
            modules.add(new ModuleConfig(DefaultModules.REST, true, Map.of(
                    DefaultModules.REST_HOST, value(form, answers, REST_HOST),
                    DefaultModules.REST_PORT, String.valueOf(port(form, answers, REST_PORT)),
                    DefaultModules.REST_API_KEY, answers.text(REST_API_KEY).trim())));
        }
        if (config.modules().discord()) {
            modules.add(new ModuleConfig("discord", true, Map.of()));
        }
        return List.copyOf(modules);
    }

    private static String value(Questionnaire form, Answers answers, String id) {
        return form.question(id).effective(answers).trim();
    }

    private static int port(Questionnaire form, Answers answers, String id) {
        String raw = value(form, answers, id);
        if (raw.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String names(List<ServiceGroup> groups) {
        return String.join(", ", groups.stream().map(ServiceGroup::getName).toList());
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
