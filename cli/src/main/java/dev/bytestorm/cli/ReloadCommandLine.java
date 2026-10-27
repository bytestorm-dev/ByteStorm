package dev.bytestorm.cli;

import dev.bytestorm.controller.group.GroupManager;
import dev.bytestorm.core.config.ConfigException;
import dev.bytestorm.core.config.ConfigService;
import dev.bytestorm.core.config.CloudConfig;

import java.util.Objects;

/**
 * Kommando {@code reload}: Konfiguration und gecachte Gruppen neu laden (§3.1, §17).
 *
 * <p>Die Konfiguration wird aus {@code config/cloud.json} neu gelesen. Felder, die
 * einen Neustart brauchen (Mongo, Redis, Runtime, Docker, Forwarding-Secret),
 * lehnt {@link ConfigService#reload()} ab — das Kommando gibt die Meldung dann
 * unverändert weiter, statt etwas zu übernehmen.
 *
 * <p>Der Gruppen-Cache wird verworfen und aus dem Storage neu gefüllt, damit
 * Änderungen an einer Gruppe auch dann greifen, wenn sie an diesem Prozess vorbei
 * geschrieben wurden.
 */
public final class ReloadCommandLine {

    /** Hilfe für {@code reload help}. */
    public static final String USAGE = """
            reload          config/cloud.json neu laden und den Gruppen-Cache verwerfen
            reload groups   nur den Gruppen-Cache neu aus dem Storage füllen
            reload config   nur config/cloud.json neu laden
            """;

    private final ConfigService config;
    private final GroupManager groups;

    /**
     * @param config Konfiguration mit {@link ConfigService#reload()}
     * @param groups Gruppen-Verwaltung mit {@link GroupManager#reloadCache()}
     */
    public ReloadCommandLine(ConfigService config, GroupManager groups) {
        this.config = Objects.requireNonNull(config, "config");
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    /**
     * Führt {@code reload} aus.
     *
     * @param tokens Kommandozeile, z. B. {@code reload} oder {@code reload groups}
     * @return Ausgabe für das Terminal
     */
    public String execute(String[] tokens) {
        String target = tokens.length > 1 ? tokens[1].toLowerCase(java.util.Locale.ROOT) : "all";
        if (target.equals("help") || target.equals("-h") || target.equals("--help")) {
            return Theme.help(USAGE.stripTrailing());
        }
        if (!target.equals("all") && !target.equals("config") && !target.equals("groups")) {
            return Theme.warn("Unbekanntes reload-Ziel: " + tokens[1] + " (reload help)");
        }

        StringBuilder out = new StringBuilder();
        if (!target.equals("groups")) {
            String configResult = reloadConfig();
            out.append(configResult);
            if (configResult.startsWith("Neustart nötig")) {
                // Eine abgelehnte Konfiguration ist kein Grund, den Cache nicht zu laden.
                out.append(System.lineSeparator()).append(reloadGroups());
                return out.toString();
            }
        }
        if (!target.equals("config")) {
            if (!out.isEmpty()) {
                out.append(System.lineSeparator());
            }
            out.append(reloadGroups());
        }
        return out.toString();
    }

    private String reloadConfig() {
        try {
            CloudConfig next = config.reload();
            return Theme.ok("Konfiguration neu geladen (Runtime %s, Scaling-Intervall %d s)"
                    .formatted(next.runtime(), next.scaling().intervalSeconds()));
        } catch (ConfigException e) {
            return Theme.warn("Neustart nötig: " + messageOf(e));
        } catch (RuntimeException e) {
            return Theme.fail("Fehler: " + messageOf(e));
        }
    }

    private String reloadGroups() {
        try {
            int count = groups.reloadCache();
            return Theme.ok("Gruppen-Cache neu geladen: " + count
                    + (count == 1 ? " Gruppe" : " Gruppen"));
        } catch (RuntimeException e) {
            return Theme.fail("Fehler: " + messageOf(e));
        }
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
