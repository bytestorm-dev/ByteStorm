package dev.bytestorm.cli;

import dev.bytestorm.api.module.Modules;
import dev.bytestorm.proto.ModuleInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Kommandos für die optionalen Module (§16, §17).
 *
 * <p>Die CLI spricht nur die öffentliche Fassade {@link Modules} an; geladen,
 * aktiviert und persistiert wird im {@code ModuleManager}. Der Zustand kommt aus
 * dem Modul selbst, damit CLI und REST dieselbe Sicht haben.
 *
 * <p>Mit {@code module set} schreibt sie Einstellungen wie den API-Key des
 * REST-Moduls in die Modul-Konfiguration, damit niemand die Collection von Hand
 * bearbeitet. Geheimnisartige Werte zeigt {@code module settings} nur redigiert
 * (§20).
 */
public final class ModuleCommandLine {

    /** Hilfe für {@code module help}. */
    public static final String USAGE = """
            module list               alle bekannten Module mit Zustand
            module info <name>        Details eines Moduls
            module settings <name>    Einstellungen eines Moduls (Geheimnisse redigiert)
            module set <name> <key> <wert>  Einstellung setzen, z. B. apiKey des REST-Moduls
            module enable <name>      Modul zur Laufzeit aktivieren
            module disable <name>     Modul stoppen
            """;

    /** Ersetzt Werte, die wie ein Geheimnis aussehen (§20). */
    private static final String REDACTED = "***";

    /** Bestandteile eines Einstellungsnamens, die auf ein Geheimnis hindeuten. */
    private static final List<String> SECRET_PARTS = List.of("key", "secret", "password", "passwd", "token");

    /** Wie lange die CLI auf ein Enable/Disable wartet. */
    private static final long TIMEOUT_SECONDS = 60;

    private final Modules modules;

    /**
     * @param modules Modul-Fassade, z. B. der {@code ModuleManager}
     */
    public ModuleCommandLine(Modules modules) {
        this.modules = Objects.requireNonNull(modules, "modules");
    }

    /**
     * Führt ein Modul-Kommando aus.
     *
     * @param tokens Kommandozeile, z. B. {@code module enable rest}
     * @return Ausgabe für das Terminal
     */
    public String execute(String[] tokens) {
        String command = tokens.length > 1 ? tokens[1].toLowerCase(Locale.ROOT) : "list";
        String name = tokens.length > 2 ? tokens[2] : null;
        return switch (command) {
            case "list" -> list();
            case "help", "-h", "--help" -> Theme.help(USAGE.stripTrailing());
            case "info" -> info(name);
            case "settings", "config" -> settings(name);
            case "set" -> set(tokens);
            case "enable", "start" -> switch (name) {
                case null -> Theme.warn("Modulname fehlt (module enable <name>)");
                default -> toggle(name, true);
            };
            case "disable", "stop" -> switch (name) {
                case null -> Theme.warn("Modulname fehlt (module disable <name>)");
                default -> toggle(name, false);
            };
            default -> Theme.warn("Unbekanntes Modul-Kommando: " + tokens[1] + " (module help)");
        };
    }

    private String list() {
        List<ModuleInfo> infos;
        try {
            infos = new ArrayList<>(modules.list());
        } catch (RuntimeException e) {
            return Theme.fail("Fehler: " + messageOf(e));
        }
        if (infos.isEmpty()) {
            return Theme.dim("Keine Module gefunden — Implementierungen liegen als Jar in lib/.");
        }
        infos.sort(Comparator.comparing(ModuleInfo::getName));
        Table table = Table.of("Name", "Aktiviert", "Zustand", "Version");
        for (ModuleInfo info : infos) {
            table.row(info.getName(), Theme.yesNo(info.getEnabled()),
                    Theme.moduleState(info.getState()),
                    info.getVersion().isBlank() ? Theme.dim("—") : info.getVersion());
        }
        return table.render();
    }

    private String info(String name) {
        if (name == null || name.isBlank()) {
            return Theme.warn("Modulname fehlt (module info <name>)");
        }
        Optional<ModuleInfo> info;
        try {
            info = modules.get(name);
        } catch (RuntimeException e) {
            return Theme.fail("Fehler: " + messageOf(e));
        }
        if (info.isEmpty()) {
            return Theme.warn("Unbekanntes Modul: " + name);
        }
        ModuleInfo module = info.get();
        return String.join(System.lineSeparator(),
                Theme.field("Name", Theme.bold(module.getName())),
                Theme.field("Status", Theme.moduleState(module.getState())),
                Theme.field("Aktiviert", Theme.yesNo(module.getEnabled())),
                Theme.field("Version", module.getVersion().isBlank()
                        ? "unbekannt" : module.getVersion()));
    }

    /**
     * Zeigt die Einstellungen eines Moduls. Werte, deren Name auf ein Geheimnis
     * hindeutet, erscheinen nur als {@code ***}.
     *
     * @param name Modulname
     * @return Tabelle für das Terminal
     */
    private String settings(String name) {
        if (name == null || name.isBlank()) {
            return Theme.warn("Modulname fehlt (module settings <name>)");
        }
        Map<String, String> values;
        try {
            values = modules.settings(name);
        } catch (RuntimeException e) {
            return Theme.fail("Fehler: " + messageOf(e));
        }
        if (values.isEmpty()) {
            return Theme.dim("Modul '" + name + "' hat keine Einstellungen.");
        }
        Table table = Table.of("Einstellung", "Wert");
        new TreeMap<>(values).forEach((key, value) -> table.row(key, display(key, value)));
        return table.render();
    }

    /**
     * Setzt eine Einstellung, z. B. {@code module set rest apiKey <wert>}. Der
     * Wert gilt ab dem nächsten Laden des Moduls; die Antwort sagt, ob dafür ein
     * Neustart der Cloud nötig ist.
     *
     * @param tokens Kommandozeile
     * @return Meldung für das Terminal
     */
    private String set(String[] tokens) {
        if (tokens.length < 5 || tokens[2].isBlank() || tokens[3].isBlank()) {
            return Theme.warn("Verwendung: module set <name> <key> <wert>");
        }
        String name = tokens[2];
        String key = tokens[3];
        String value = String.join(" ", Arrays.copyOfRange(tokens, 4, tokens.length)).trim();
        try {
            modules.setSetting(name, key, value);
        } catch (RuntimeException e) {
            return Theme.fail("Fehler: " + messageOf(e));
        }
        return running(name)
                ? Theme.warn("Einstellung gespeichert: " + name + "." + key
                + " — das Modul läuft mit den alten Werten weiter, wirksam nach einem Neustart der Cloud")
                : Theme.ok("Einstellung gespeichert: " + name + "." + key
                + " — wirksam beim nächsten Aktivieren (module enable " + name + ")");
    }

    /**
     * @param name Modulname
     * @return {@code true}, wenn das Modul gerade läuft
     */
    private boolean running(String name) {
        try {
            return modules.isEnabled(name);
        } catch (RuntimeException e) {
            // Der Hinweis darunter gilt auch ohne Zustand.
            return false;
        }
    }

    private static String display(String key, String value) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String part : SECRET_PARTS) {
            if (lower.contains(part)) {
                return REDACTED;
            }
        }
        return value;
    }

    private String toggle(String name, boolean enable) {
        try {
            CompletableFuture<Void> future = enable ? modules.enable(name) : modules.disable(name);
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return Theme.fail("Zeitüberschreitung: Modul " + name + " reagiert nicht ("
                    + TIMEOUT_SECONDS + " s)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Theme.warn("Abgebrochen: Modul " + name);
        } catch (ExecutionException | CompletionException e) {
            return Theme.fail("Fehler: " + messageOf(e.getCause() == null ? e : e.getCause()));
        } catch (RuntimeException e) {
            return Theme.fail("Fehler: " + messageOf(e));
        }
        return Theme.ok((enable ? "Modul aktiviert: " : "Modul deaktiviert: ") + name);
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
