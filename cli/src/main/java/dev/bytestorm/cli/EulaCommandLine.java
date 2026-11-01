package dev.bytestorm.cli;

import dev.bytestorm.core.template.Eula;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code eula} — Zustimmung zur Minecraft-EULA im Server-Template (§17).
 *
 * <p>Paper startet ohne {@code eula.txt} nicht, die Zustimmung ist aber eine
 * Erklärung des Betreibers gegenüber Mojang. Der Erststart-Wizard fragt sie
 * einmal; dieses Kommando ändert sie später — auch für eine Cloud, die ohne
 * Wizard eingerichtet wurde — und zeigt, wie viele Service-Ordner eine eigene
 * {@code eula.txt} mitbringen.
 *
 * <p>Die Datei wird geschrieben, nicht gelöscht: Ein erneutes Anwenden der
 * Templates würde eine gelöschte Datei sonst wieder mit {@code eula=true}
 * auffüllen und die Rücknahme stillschweigend aufheben.
 */
public final class EulaCommandLine {

    /** Hilfe für {@code eula}. */
    public static final String USAGE = """
            eula status             Zustand der Minecraft-EULA im Server-Template
            eula accept             Zustimmung festhalten (eula=true)
            eula revoke             Zustimmung zurücknehmen (eula=false)
            """;

    private final Path directory;

    /**
     * @param directory Cloud-Verzeichnis mit {@code templates/} und {@code static/}
     */
    public EulaCommandLine(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /**
     * @param args geteilte Eingabe, z. B. {@code eula accept}
     * @return Ausgabe für die Konsole
     */
    public String execute(String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        return switch (action) {
            case "status" -> status();
            case "accept" -> write(true);
            case "revoke" -> write(false);
            case "help", "-h", "--help" -> Theme.help(USAGE.stripTrailing());
            default -> Theme.warn("Unbekannter EULA-Befehl: " + args[1]
                    + " (eula status|accept|revoke)");
        };
    }

    private String status() {
        List<Path> services = Eula.servicesWithEula(directory);
        String state = switch (Eula.status(directory)) {
            case ACCEPTED -> "zugestimmt";
            case REFUSED -> "abgelehnt";
            case MISSING -> "nicht entschieden";
        };
        StringBuilder out = new StringBuilder()
                .append(Theme.field("EULA", state + " " + Theme.dim("(templates/" + Eula.FILE + ")")))
                .append(System.lineSeparator())
                .append(Theme.field("Services", services.isEmpty()
                        ? "keine eigenen eula.txt — neue Services übernehmen das Template"
                        : services.size() + (services.size() == 1 ? " Ordner" : " Ordner")
                                + " mit eigener eula.txt"));
        if (Eula.status(directory) == Eula.State.MISSING) {
            out.append(System.lineSeparator())
                    .append(Theme.dim("  'eula accept' hält die Zustimmung fest, 'eula revoke' nimmt sie zurück."));
        }
        return out.toString();
    }

    private String write(boolean accept) {
        try {
            List<Path> written = accept ? Eula.accept(directory) : Eula.revoke(directory);
            StringBuilder out = new StringBuilder(accept
                    ? Theme.field("EULA", "Zustimmung gespeichert — Paper darf starten")
                    : Theme.field("EULA", "Zustimmung zurückgenommen — Paper verweigert den Start"));
            out.append(System.lineSeparator())
                    .append("  Template: ").append(written.get(0));
            if (written.size() > 1) {
                out.append(System.lineSeparator())
                        .append("  Service-Ordner angepasst: ").append(written.size() - 1);
            }
            out.append(System.lineSeparator())
                    .append(Theme.dim("  Laufende Paper-Services lesen die Datei beim Start —"))
                    .append(Theme.dim(" 'service restart <name>' übernimmt die Änderung."));
            return out.toString();
        } catch (IOException e) {
            return Theme.fail("EULA konnte nicht gespeichert werden: " + e.getMessage());
        }
    }
}
