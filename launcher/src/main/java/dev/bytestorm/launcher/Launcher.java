package dev.bytestorm.launcher;

import dev.bytestorm.launcher.download.LibraryInstaller;
import dev.bytestorm.launcher.layout.CloudLayout;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Einstiegspunkt des Launchers (§5.1).
 *
 * <p>Der Launcher selbst bleibt schlank: Er legt das Verzeichnislayout an,
 * baut den {@link LibClassLoader} über {@code lib/} und lädt daraus die
 * Bootstrap-Klasse. Damit kommen die Laufzeit-Abhängigkeiten aus {@code lib/}
 * statt aus dem Classpath des Launcher-Jars; fehlt eine Jar, greift der Loader
 * auf den Parent zurück.
 *
 * <p>Die Bootstrap-Klasse wird bewusst nur über ihren Namen geladen, damit
 * dieses Jar ohne Cloud-Abhängigkeiten kompilierbar bleibt. Aus demselben Grund
 * lädt {@link LibraryInstaller} die Fremd-Jars vorher mit reinem JDK-Code nach:
 * Erst wenn {@code lib/} vollständig ist, öffnet sich der Classloader.
 */
public final class Launcher {

    /** Vollqualifizierter Name der Bootstrap-Klasse. */
    public static final String BOOTSTRAP_CLASS = "dev.bytestorm.launcher.Bootstrap";

    /** Uhrzeit der Fehlerzeile, im selben Format wie die Konsolenausgabe. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Launcher() {
    }

    /**
     * @param args optional {@code --dir <Pfad>}, {@code --help}
     */
    public static void main(String[] args) {
        if (LauncherArguments.wantsHelp(args)) {
            System.out.println(LauncherArguments.USAGE);
            return;
        }
        if (LauncherArguments.noColor(args)) {
            // Die Konsole liest die Property beim Aufbau (§17); sie wirkt prozessweit.
            System.setProperty(LauncherArguments.COLOR_PROPERTY, "never");
        }

        Path directory = LauncherArguments.cloudDirectory(args);
        CloudLayout layout = new CloudLayout(directory);
        try {
            layout.create();
        } catch (IOException e) {
            fail("Verzeichnislayout konnte nicht angelegt werden: " + e.getMessage());
            return;
        }
        if (!librariesReady(layout)) {
            return;
        }

        try (LibClassLoader loader = LibClassLoader.open(layout)) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> bootstrap = Class.forName(BOOTSTRAP_CLASS, true, loader);
            bootstrap.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            fail(messageOf(cause));
        } catch (ReflectiveOperationException | IOException e) {
            fail(messageOf(e));
        }
    }

    /**
     * Prüft und ergänzt die Fremd-Jars in {@code lib/}. Die Liste
     * {@code lib/dependencies.txt} entsteht im Build; fehlt sie, bringt die
     * Distribution ihre Jars selbst mit und es passiert nichts.
     *
     * @param layout Cloud-Layout
     * @return {@code true}, wenn alle Bibliotheken bereitstehen
     */
    private static boolean librariesReady(CloudLayout layout) {
        LibraryInstaller.Result result;
        try {
            result = LibraryInstaller.install(layout, System.out);
        } catch (RuntimeException e) {
            fail("Bibliotheken nicht prüfbar: " + messageOf(e));
            return false;
        }
        if (result.complete()) {
            return true;
        }
        List<LibraryInstaller.Missing> missing = result.missing();
        StringBuilder message = new StringBuilder(layout.lib() + " ist unvollständig — "
                + missing.size() + " Bibliothek(en) fehlen:");
        for (LibraryInstaller.Missing entry : missing) {
            message.append(System.lineSeparator()).append(entry.describe());
        }
        message.append(System.lineSeparator())
                .append("Bitte die Internetverbindung prüfen und erneut starten oder die Dateien ")
                .append("an die genannten Pfade legen.");
        fail(message.toString());
        return false;
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /**
     * Formatiert eine Meldung wie die Konsolenzeilen der Cloud, damit auch ein
     * fehlgeschlagener Start dieselbe Form hat: {@code 16:42:01|ERROR Text}. Das
     * Format steht hier statt in {@code Logging}, weil der Launcher vor dem
     * Classloader läuft und nur JDK-Klassen sehen darf.
     *
     * @param message Meldung ohne Präfix
     * @return die fertige Zeile
     */
    static String errorLine(String message) {
        return LocalTime.now().format(TIME) + "|ERROR " + message;
    }

    private static void fail(String message) {
        System.err.println(errorLine(message));
        System.exit(1);
    }
}
