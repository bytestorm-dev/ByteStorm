package dev.bytestorm.node.runtime;

import dev.bytestorm.api.service.LaunchOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Baut die Kommandozeile für die {@link ProcessRuntime} (Konzept §12.2).
 *
 * <p>Das Ergebnis ist eine Argumentliste für {@link ProcessBuilder}, die ohne
 * Shell ausgeführt wird. Gruppennamen, JVM-Argumente und Pfade können deshalb
 * keine zusätzlichen Kommandos einschleusen; Argumente mit NUL, CR oder LF
 * werden abgelehnt.
 */
final class ProcessCommand {

    private static final String NO_GUI = "nogui";

    private ProcessCommand() {
    }

    /**
     * @param javaBinary Java-Binary
     * @param platformJar Platform-Jar
     * @param options    Startparameter
     * @return {@code java -Xms<S> -Xmx<S> <jvmArgs> -jar <platformJar> <javaArgs> nogui}
     */
    static List<String> build(Path javaBinary, Path platformJar, LaunchOptions options) {
        List<String> command = new ArrayList<>();
        command.add(javaBinary.toString());
        command.add("-Xms" + options.memoryMb() + "M");
        command.add("-Xmx" + options.memoryMb() + "M");
        command.addAll(validate(options.jvmArgs(), "jvmArgs"));
        command.add("-jar");
        command.add(platformJar.toString());

        List<String> javaArgs = validate(options.javaArgs(), "javaArgs");
        command.addAll(javaArgs);
        if (javaArgs.stream().noneMatch(NO_GUI::equalsIgnoreCase)) {
            command.add(NO_GUI);
        }
        return List.copyOf(command);
    }

    private static List<String> validate(List<String> args, String field) {
        List<String> checked = new ArrayList<>(args.size());
        for (String arg : args) {
            if (arg == null) {
                throw new IllegalArgumentException(field + " darf keine null-Einträge enthalten");
            }
            if (arg.indexOf('\0') >= 0 || arg.indexOf('\n') >= 0 || arg.indexOf('\r') >= 0) {
                throw new IllegalArgumentException(field + " enthält ein unerlaubtes Steuerzeichen: " + arg);
            }
            checked.add(arg);
        }
        return checked;
    }

    /**
     * @return der Name des Java-Binaries der laufenden JVM
     */
    static Path currentJava() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path binary = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        return binary.toAbsolutePath().normalize();
    }
}
