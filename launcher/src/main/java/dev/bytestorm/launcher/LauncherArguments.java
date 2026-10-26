package dev.bytestorm.launcher;

import lombok.experimental.UtilityClass;

import java.nio.file.Path;

/**
 * Kommandozeilenargumente des Launchers.
 */
@UtilityClass
public final class LauncherArguments {

    /** Aufrufhinweis für {@code --help}. */
    public static final String USAGE = "Aufruf: java -jar bytestorm.jar [--dir <Cloud-Verzeichnis>]";

    /**
     * Liest {@code --dir <Pfad>} bzw. {@code -d <Pfad>}.
     *
     * @param args Kommandozeilenargumente
     * @return das Cloud-Verzeichnis, ohne Angabe das Arbeitsverzeichnis
     */
    public static Path cloudDirectory(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--dir".equals(args[i]) || "-d".equals(args[i])) {
                return Path.of(args[i + 1]).toAbsolutePath().normalize();
            }
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    /**
     * @param args Kommandozeilenargumente
     * @return {@code true}, wenn nur {@code --help} oder {@code -h} übergeben wurde
     */
    public static boolean wantsHelp(String[] args) {
        return args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]));
    }
}
