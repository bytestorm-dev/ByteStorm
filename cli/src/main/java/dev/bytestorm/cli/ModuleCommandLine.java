package dev.bytestorm.cli;

import dev.bytestorm.api.module.Modules;
import dev.bytestorm.proto.ModuleInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
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
 */
public final class ModuleCommandLine {

    /** Hilfe für {@code module help}. */
    public static final String USAGE = """
            module list               alle bekannten Module mit Zustand
            module info <name>        Details eines Moduls
            module enable <name>      Modul zur Laufzeit aktivieren
            module disable <name>     Modul stoppen
            """;

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
            case "help", "-h", "--help" -> USAGE.stripTrailing();
            case "info" -> info(name);
            case "enable", "start" -> switch (name) {
                case null -> "Modulname fehlt (module enable <name>)";
                default -> toggle(name, true);
            };
            case "disable", "stop" -> switch (name) {
                case null -> "Modulname fehlt (module disable <name>)";
                default -> toggle(name, false);
            };
            default -> "Unbekanntes Modul-Kommando: " + tokens[1] + " (module help)";
        };
    }

    private String list() {
        List<ModuleInfo> infos;
        try {
            infos = new ArrayList<>(modules.list());
        } catch (RuntimeException e) {
            return "Fehler: " + messageOf(e);
        }
        if (infos.isEmpty()) {
            return "Keine Module gefunden — Implementierungen liegen als Jar in lib/.";
        }
        infos.sort(Comparator.comparing(ModuleInfo::getName));
        StringJoiner out = new StringJoiner(System.lineSeparator());
        out.add("Name\tStatus\tVersion");
        for (ModuleInfo info : infos) {
            out.add(info.getName() + "\t" + status(info) + "\t"
                    + (info.getVersion().isBlank() ? "—" : info.getVersion()));
        }
        return out.toString();
    }

    private String info(String name) {
        if (name == null || name.isBlank()) {
            return "Modulname fehlt (module info <name>)";
        }
        Optional<ModuleInfo> info;
        try {
            info = modules.get(name);
        } catch (RuntimeException e) {
            return "Fehler: " + messageOf(e);
        }
        if (info.isEmpty()) {
            return "Unbekanntes Modul: " + name;
        }
        ModuleInfo module = info.get();
        return """
                Name: %s
                Status: %s
                Aktiviert: %s
                Version: %s
                """.formatted(module.getName(), module.getState(), module.getEnabled(),
                module.getVersion().isBlank() ? "unbekannt" : module.getVersion()).stripTrailing();
    }

    private String toggle(String name, boolean enable) {
        try {
            CompletableFuture<Void> future = enable ? modules.enable(name) : modules.disable(name);
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return "Zeitüberschreitung: Modul " + name + " reagiert nicht ("
                    + TIMEOUT_SECONDS + " s)";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Abgebrochen: Modul " + name;
        } catch (ExecutionException | CompletionException e) {
            return "Fehler: " + messageOf(e.getCause() == null ? e : e.getCause());
        } catch (RuntimeException e) {
            return "Fehler: " + messageOf(e);
        }
        return (enable ? "Modul aktiviert: " : "Modul deaktiviert: ") + name;
    }

    private static String status(ModuleInfo info) {
        if (!info.getEnabled()) {
            return "deaktiviert (" + info.getState() + ")";
        }
        return "aktiviert (" + info.getState() + ")";
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
