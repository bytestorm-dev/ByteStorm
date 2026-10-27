package dev.bytestorm.launcher;

import dev.bytestorm.launcher.layout.CloudLayout;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;

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
 * dieses Jar ohne Cloud-Abhängigkeiten kompilierbar bleibt.
 */
public final class Launcher {

    /** Vollqualifizierter Name der Bootstrap-Klasse. */
    public static final String BOOTSTRAP_CLASS = "dev.bytestorm.launcher.Bootstrap";

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

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static void fail(String message) {
        System.err.println("ByteStorm: " + message);
        System.exit(1);
    }
}
