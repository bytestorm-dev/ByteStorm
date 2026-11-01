package dev.bytestorm.launcher.wizard;

import dev.bytestorm.api.service.Platforms;
import dev.bytestorm.core.config.MongoSettings;
import dev.bytestorm.core.mongo.MongoProvider;
import dev.bytestorm.core.mongo.repository.GroupRepository;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Fragt beim Erststart die Gruppen ab (§5.1, §9.1).
 *
 * <p>Die Cloud bringt bewusst keine Gruppen mit: Welche Dienste laufen sollen,
 * weiß nur der Betreiber. Der Wizard zeigt je Gruppe dasselbe Formular — Name,
 * Typ, Plattform, Modus, Servicezahl, Spieler, Speicher und Fallback — und fragt
 * am Ende, ob eine weitere folgt, sodass ein Netzwerkverbund aus Proxy und
 * mehreren Server-Gruppen in einem Lauf entsteht.
 *
 * <p>Die Prüfungen entsprechen {@code GroupManager.validate}: Name und Zahlen
 * liegen im erlaubten Bereich, ein Proxy belegt genau einen Service, und Typ und
 * Plattform widersprechen sich nicht. Sonst startet etwa ein Velocity-Jar als
 * Server-Gruppe — falsche Templates, falscher Port-Pool.
 */
public final class GroupSetup {

    /** ID des Gruppennamens. */
    public static final String NAME = "group.name";
    /** ID der Rolle. */
    public static final String TYPE = "group.type";
    /** ID der Plattform. */
    public static final String PLATFORM = "group.platform";
    /** ID der Lebensdauer. */
    public static final String MODE = "group.mode";
    /** ID der minimalen Servicezahl. */
    public static final String MIN = "group.min";
    /** ID der maximalen Servicezahl, {@code 0} steht für unbegrenzt. */
    public static final String MAX = "group.max";
    /** ID der Spieler je Service. */
    public static final String MAX_PLAYERS = "group.maxPlayers";
    /** ID des Speichers je Service. */
    public static final String MEMORY = "group.memory";
    /** ID des Fallback-Flags. */
    public static final String FALLBACK = "group.fallback";
    /** ID der Rückfrage nach einer weiteren Gruppe. */
    public static final String MORE = "group.more";

    /** Dieselbe Namensregel wie im {@code GroupManager}; Namen werden zu Ordnernamen. */
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private static final int MAX_SERVICES = 1_000;
    private static final int MAX_PLAYERS_LIMIT = 100_000;
    private static final int MIN_MEMORY = 128;
    private static final int MAX_MEMORY = 65_536;

    private GroupSetup() {
    }

    /**
     * Fragt Gruppen ab, bis der Benutzer keine weitere mehr anlegen will.
     *
     * @param io Ein-/Ausgabe
     * @return mindestens eine Gruppe, in Eingabereihenfolge
     * @throws WizardAbortedException wenn der Benutzer abbricht
     */
    public static List<ServiceGroup> ask(WizardIo io) {
        Objects.requireNonNull(io, "io");
        List<ServiceGroup> groups = new ArrayList<>();
        while (true) {
            Questionnaire form = form(groups);
            Answers answers = io.fill(form, Answers.empty(), QuestionnaireNotice.none());
            groups.add(definition(form, answers));
            if (!answers.flag(MORE)) {
                return List.copyOf(groups);
            }
        }
    }

    /**
     * Speichert die Gruppen, sofern ihre Namen noch frei sind. Eine vorhandene
     * Gruppe bleibt unangetastet — der Betreiber hat sie bewusst angepasst.
     *
     * @param settings MongoDB-Verbindung
     * @param groups   Definitionen aus {@link #ask}
     * @return die Namen der tatsächlich angelegten Gruppen
     */
    public static List<String> create(MongoSettings settings, List<ServiceGroup> groups) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(groups, "groups");
        if (groups.isEmpty()) {
            return List.of();
        }
        List<String> created = new ArrayList<>(groups.size());
        try (MongoProvider mongo = MongoProvider.connect(settings)) {
            GroupRepository repository = new GroupRepository(mongo);
            for (ServiceGroup group : groups) {
                if (repository.find(group.getName()).isPresent()) {
                    continue;
                }
                repository.upsert(group);
                created.add(group.getName());
            }
        }
        return List.copyOf(created);
    }

    /**
     * @param already bereits angelegte Gruppen; ihre Namen dürfen nicht erneut vergeben werden
     * @return das Formular für die nächste Gruppe
     */
    static Questionnaire form(List<ServiceGroup> already) {
        List<String> taken = already.stream().map(ServiceGroup::getName).toList();
        return new Questionnaire("Gruppe " + (already.size() + 1),
                "Welche Dienste soll die Cloud starten?",
                List.of(
                        Question.text(NAME, "Name")
                                .description("1 bis 32 Zeichen aus Buchstaben, Ziffern, '-' und '_'.")
                                .required(true)
                                .validate((value, answers) -> nameError(value, taken))
                                .build(),
                        Question.select(TYPE, "Typ")
                                .description("SERVER sind Spielserver, PROXY der Eingang für Spieler.")
                                .option(ServiceType.SERVER.name(), ServiceType.SERVER.name(),
                                        "Spielserver hinter dem Proxy.")
                                .option(ServiceType.PROXY.name(), ServiceType.PROXY.name(),
                                        "Eingang für Spieler; genau ein Service.")
                                .defaultValue(ServiceType.SERVER.name())
                                .build(),
                        Question.text(PLATFORM, "Plattform")
                                .description("Projektname, z. B. paper für Backends oder velocity für den Proxy.")
                                .defaultValue("paper")
                                .required(true)
                                .validate(GroupSetup::platformError)
                                .build(),
                        Question.select(MODE, "Modus")
                                .description("STATIC bleibt bestehen, DYNAMIC folgt der Skalierung.")
                                .option(ServiceMode.DYNAMIC.name(), ServiceMode.DYNAMIC.name(),
                                        "Folgt der Auslastung, Services kommen und gehen.")
                                .option(ServiceMode.STATIC.name(), ServiceMode.STATIC.name(),
                                        "Feste Zahl, nichts wird automatisch gestoppt.")
                                .defaultValue(ServiceMode.DYNAMIC.name())
                                .build(),
                        Question.text(MIN, "Min. Services")
                                .description("Untergrenze der Skalierung.")
                                .defaultValue("1")
                                .validate(GroupSetup::minError)
                                .build(),
                        Question.text(MAX, "Max. Services")
                                .description("Obergrenze der Skalierung; 0 heißt unbegrenzt.")
                                .defaultValue("1")
                                .validate(GroupSetup::maxError)
                                .build(),
                        Question.text(MAX_PLAYERS, "Spieler je Service")
                                .description("Grundlage der Auslastung; für Server-Gruppen Pflicht.")
                                .defaultValue("100")
                                .validate((value, answers) -> rangeError(value, 1, MAX_PLAYERS_LIMIT,
                                        "Bitte eine Spielerzahl zwischen 1 und " + MAX_PLAYERS_LIMIT
                                                + " eingeben."))
                                .visibleWhen(answers -> !isProxy(answers))
                                .build(),
                        Question.text(MEMORY, "Speicher je Service")
                                .description("Heap-Größe in MB.")
                                .defaultValue("1024")
                                .validate((value, answers) -> rangeError(value, MIN_MEMORY, MAX_MEMORY,
                                        "Bitte einen Speicher zwischen " + MIN_MEMORY + " und "
                                                + MAX_MEMORY + " MB eingeben."))
                                .build(),
                        Question.confirm(FALLBACK, "Fallback-Ziel")
                                .description("Neue Spieler landen hier, wenn der Proxy sie weiterverteilt.")
                                .visibleWhen(answers -> !isProxy(answers))
                                .build(),
                        Question.confirm(MORE, "Weitere Gruppe anlegen?")
                                .description("Zum Beispiel nach dem Proxy noch eine Server-Gruppe.")
                                .build()));
    }

    /**
     * Baut die Definition aus den Antworten. Alle Werte sind an dieser Stelle
     * geprüft; leere Textfelder greifen auf ihre Vorschlagswerte zurück.
     *
     * @param form    Formular der Gruppe
     * @param answers Antworten
     * @return die Gruppe
     */
    static ServiceGroup definition(Questionnaire form, Answers answers) {
        ServiceType type = ServiceType.valueOf(form.question(TYPE).currentId(answers));
        boolean fallback = type == ServiceType.SERVER && answers.flag(FALLBACK);
        return ServiceGroup.newBuilder()
                .setName(answers.text(NAME).trim())
                .setType(type)
                .setPlatform(form.question(PLATFORM).effective(answers).trim())
                .setMode(ServiceMode.valueOf(form.question(MODE).currentId(answers)))
                .setMinServices(number(form, answers, MIN))
                .setMaxServices(number(form, answers, MAX))
                // Ein Proxy zählt keine Spieler; das Feld bleibt ihm erspart.
                .setMaxPlayers(type == ServiceType.PROXY ? 0 : number(form, answers, MAX_PLAYERS))
                .setMemory(number(form, answers, MEMORY))
                .setFallback(fallback)
                .setFallbackPriority(fallback ? 1 : 0)
                .build();
    }

    private static boolean isProxy(Answers answers) {
        return ServiceType.PROXY.name().equals(answers.text(TYPE));
    }

    private static String nameError(String value, List<String> taken) {
        String name = value == null ? "" : value.trim();
        if (!NAME_PATTERN.matcher(name).matches()) {
            return "Name muss 1 bis 32 Zeichen aus Buchstaben, Ziffern, '-' und '_' enthalten.";
        }
        boolean duplicate = taken.stream().anyMatch(name::equalsIgnoreCase);
        return duplicate ? "Gruppe '" + name + "' ist in diesem Lauf schon angelegt." : null;
    }

    private static String platformError(String value, Answers answers) {
        String platform = value == null ? "" : value.trim();
        if (platform.isEmpty()) {
            return "Plattform ist Pflicht (z. B. paper, velocity).";
        }
        if (platform.contains("/") || platform.contains("\\")) {
            return "Plattform darf keine Pfadtrenner enthalten.";
        }
        boolean proxyPlatform = Platforms.isProxy(platform);
        if (proxyPlatform && !isProxy(answers)) {
            return "'" + platform + "' ist eine Proxy-Plattform — Typ PROXY wählen.";
        }
        if (!proxyPlatform && isProxy(answers)) {
            return "Typ PROXY braucht eine Proxy-Plattform (z. B. velocity), nicht '" + platform + "'.";
        }
        return null;
    }

    private static String minError(String value, Answers answers) {
        String message = rangeError(value, 0, MAX_SERVICES,
                "Bitte eine Zahl zwischen 0 und " + MAX_SERVICES + " eingeben.");
        if (message != null) {
            return message;
        }
        int max = parse(answers.text(MAX));
        if (answers.has(MAX) && max != 0 && parse(value) > max) {
            return "Min. Services (" + parse(value) + ") darf Max. Services (" + max
                    + ") nicht überschreiten.";
        }
        return null;
    }

    private static String maxError(String value, Answers answers) {
        String message = rangeError(value, 0, MAX_SERVICES,
                "Bitte eine Zahl zwischen 0 und " + MAX_SERVICES + " eingeben.");
        if (message != null) {
            return message;
        }
        int max = parse(value);
        if (isProxy(answers) && max != 1) {
            // Ein Proxy belegt ports.proxy und damit genau einen Service.
            return "Ein Proxy belegt genau einen Service — Max. Services auf 1 setzen.";
        }
        int min = parse(answers.text(MIN));
        if (max != 0 && min > max) {
            return "Max. Services (" + max + ") darf Min. Services (" + min + ") nicht unterschreiten.";
        }
        return null;
    }

    private static String rangeError(String value, int min, int max, String message) {
        int number = parse(value);
        return number < min || number > max ? message : null;
    }

    private static int parse(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int number(Questionnaire form, Answers answers, String id) {
        int number = parse(form.question(id).effective(answers));
        return Math.max(number, 0);
    }
}
