package dev.bytestorm.core.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Die Minecraft-EULA im Server-Template (§17).
 *
 * <p>Paper startet nur mit einer {@code eula.txt}, die {@code eula=true} enthält.
 * Die Zustimmung ist aber eine Erklärung des Betreibers gegenüber Mojang: Sie
 * entsteht deshalb nie von selbst, sondern nur über den Erststart-Wizard oder
 * {@code eula accept}. {@link #revoke} nimmt sie zurück, indem es {@code eula=false}
 * schreibt — nicht durch Löschen, damit ein erneutes Anwenden der Templates die
 * alte Zustimmung nicht wieder hervorholt.
 *
 * <p>Beide Richtungen wirken sofort auf das Template und auf alle vorhandenen
 * Service-Ordner, die schon eine {@code eula.txt} haben. Laufende Paper-Prozesse
 * lesen die Datei nur beim Start; sie übernehmen die Änderung beim nächsten
 * Neustart.
 */
public final class Eula {

    /** Relativer Pfad im Template-Verzeichnis. */
    public static final String FILE = "SERVER/eula.txt";

    /** Adresse der EULA, damit die Datei erklärt, wozu sie da ist. */
    public static final String URL = "https://aka.ms/MinecraftEULA";

    /** Ordner, in denen Service-Arbeitsverzeichnisse liegen (siehe {@code CloudLayout}). */
    private static final List<String> SERVICE_ROOTS = List.of("static", "running");

    /** Zustand der Zustimmung im Template. */
    public enum State {

        /** {@code eula=true} — Paper startet. */
        ACCEPTED,

        /** {@code eula=false} — der Betreiber hat widersprochen. */
        REFUSED,

        /** Keine Datei — es wurde noch nichts entschieden. */
        MISSING
    }

    private Eula() {
    }

    /**
     * @param cloudDirectory Cloud-Verzeichnis
     * @return die {@code eula.txt} im Server-Template
     */
    public static Path file(Path cloudDirectory) {
        return cloudDirectory.resolve("templates").resolve(FILE);
    }

    /**
     * @param cloudDirectory Cloud-Verzeichnis
     * @return der Zustand im Template
     */
    public static State status(Path cloudDirectory) {
        Path file = file(cloudDirectory);
        if (!Files.isRegularFile(file)) {
            return State.MISSING;
        }
        try {
            return accepted(Files.readString(file, StandardCharsets.UTF_8))
                    ? State.ACCEPTED : State.REFUSED;
        } catch (IOException e) {
            return State.MISSING;
        }
    }

    /**
     * Hält die Zustimmung fest: schreibt {@code eula=true} ins Server-Template und
     * in jede vorhandene {@code eula.txt} eines Service-Ordners.
     *
     * @param cloudDirectory Cloud-Verzeichnis
     * @return die geschriebenen Dateien, das Template zuerst
     * @throws IOException wenn eine Datei nicht geschrieben werden kann
     */
    public static List<Path> accept(Path cloudDirectory) throws IOException {
        return write(cloudDirectory, true);
    }

    /**
     * Nimmt die Zustimmung zurück: schreibt {@code eula=false} an dieselben Stellen.
     *
     * @param cloudDirectory Cloud-Verzeichnis
     * @return die geschriebenen Dateien, das Template zuerst
     * @throws IOException wenn eine Datei nicht geschrieben werden kann
     */
    public static List<Path> revoke(Path cloudDirectory) throws IOException {
        return write(cloudDirectory, false);
    }

    /**
     * @param cloudDirectory Cloud-Verzeichnis
     * @return Service-Ordner, die bereits eine {@code eula.txt} enthalten
     */
    public static List<Path> servicesWithEula(Path cloudDirectory) {
        List<Path> found = new ArrayList<>();
        for (String directory : SERVICE_ROOTS) {
            Path root = cloudDirectory.resolve(directory);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> children = Files.list(root)) {
                children.filter(Files::isDirectory)
                        .filter(child -> Files.isRegularFile(child.resolve("eula.txt")))
                        .sorted()
                        .forEach(found::add);
            } catch (IOException e) {
                // Ein unlesbares Verzeichnis ändert nichts an der Zustimmung selbst.
            }
        }
        return List.copyOf(found);
    }

    /**
     * @param accepted {@code true} für Zustimmung
     * @return der Inhalt der {@code eula.txt}
     */
    public static String content(boolean accepted) {
        String value = accepted ? "true" : "false";
        String explanation = accepted
                ? "eula=true ist die Zustimmung zur Minecraft-EULA"
                : "eula=false heißt: keine Zustimmung zur Minecraft-EULA. Paper startet erst wieder,\n"
                + "# wenn der Betreiber mit 'eula accept' zustimmt";
        return """
                # Von ByteStorm angelegtes Standard-Template.
                # %s (%s).
                eula=%s
                """.formatted(explanation, URL, value);
    }

    /**
     * @param content Inhalt einer {@code eula.txt}
     * @return {@code true}, wenn darin {@code eula=true} steht
     */
    public static boolean accepted(String content) {
        // Properties-Schreibweise erlaubt Leerzeichen um das Gleichheitszeichen; Kommentare
        // zählen nicht, sonst würde ein erklärender Satz zur Zustimmung.
        return content.lines()
                .map(String::trim)
                .filter(line -> !line.startsWith("#"))
                .map(line -> line.replace(" ", ""))
                .filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith("eula="))
                .map(line -> line.substring("eula=".length()))
                .anyMatch("true"::equalsIgnoreCase);
    }

    private static List<Path> write(Path cloudDirectory, boolean accepted) throws IOException {
        Path template = file(cloudDirectory);
        Files.createDirectories(template.getParent());
        Files.writeString(template, content(accepted), StandardCharsets.UTF_8);
        List<Path> written = new ArrayList<>();
        written.add(template);
        for (Path service : servicesWithEula(cloudDirectory)) {
            Path file = service.resolve("eula.txt");
            Files.writeString(file, content(accepted), StandardCharsets.UTF_8);
            written.add(file);
        }
        return List.copyOf(written);
    }
}
