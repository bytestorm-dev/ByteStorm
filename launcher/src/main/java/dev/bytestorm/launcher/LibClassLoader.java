package dev.bytestorm.launcher;

import dev.bytestorm.launcher.layout.CloudLayout;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lädt Jars aus {@code lib/} (§5.1).
 *
 * <p><b>Strategie:</b> Child-First für alles, was die Cloud selbst mitbringt,
 * Parent-First für die geteilten Verträge. So kann ein Modul oder eine Bridge
 * eigene Bibliotheksversionen mitbringen (z. B. eine andere JDA), während
 * {@code api}, {@code proto}, Protobuf und SLF4J zwingend dieselben Klassen
 * bleiben — sonst wären {@code Message}- und Listener-Typen zwischen Cloud und
 * Plugin nicht identisch und jedes Casting würde scheitern.
 *
 * <p>Die Jars werden nach Dateinamen sortiert geladen, damit die Auflösung bei
 * mehreren Kandidaten reproduzierbar ist. Fehlt {@code lib/} oder ist es leer,
 * delegiert der Loader vollständig an den Parent.
 *
 * <p>Bridge-Plugins ({@code lib/bridge-<platform>.jar}) bleiben außen vor: Sie
 * laufen im Minecraft-Server, nicht in der Cloud (§12.2).
 */
public final class LibClassLoader extends URLClassLoader {

    /** Pakete, die immer vom Parent kommen, damit Typ-Identität erhalten bleibt. */
    public static final List<String> SHARED_PREFIXES = List.of(
            "dev.bytestorm.api.",
            "dev.bytestorm.proto.",
            "com.google.protobuf.",
            "org.slf4j.");

    private LibClassLoader(URL[] urls, ClassLoader parent) {
        super("bytestorm-lib", urls, parent);
    }

    /**
     * @param layout Cloud-Layout mit {@code lib/}
     * @return Loader über alle Jars in {@code lib/}
     * @throws IOException wenn {@code lib/} nicht lesbar ist
     */
    public static LibClassLoader open(CloudLayout layout) throws IOException {
        return open(layout.lib(), LibClassLoader.class.getClassLoader());
    }

    /**
     * @param libDirectory {@code lib/}-Verzeichnis
     * @return Loader über alle Jars in {@code lib/}
     * @throws IOException wenn {@code lib/} nicht lesbar ist
     */
    public static LibClassLoader open(Path libDirectory) throws IOException {
        return open(libDirectory, LibClassLoader.class.getClassLoader());
    }

    /**
     * @param libDirectory {@code lib/}-Verzeichnis
     * @param parent       Parent für geteilte und nicht vorhandene Klassen
     * @return Loader über alle Jars in {@code lib/}
     * @throws IOException wenn {@code lib/} nicht lesbar ist
     */
    public static LibClassLoader open(Path libDirectory, ClassLoader parent) throws IOException {
        List<URL> urls = new ArrayList<>();
        for (Path jar : jars(libDirectory)) {
            if (!isCloudJar(jar)) {
                continue;
            }
            urls.add(jar.toUri().toURL());
        }
        return new LibClassLoader(urls.toArray(URL[]::new), parent);
    }

    /**
     * @param libDirectory {@code lib/}-Verzeichnis
     * @return alle Jars, sortiert nach Dateiname; leer, wenn das Verzeichnis fehlt
     * @throws IOException wenn das Verzeichnis nicht lesbar ist
     */
    public static List<Path> jars(Path libDirectory) throws IOException {
        if (!Files.isDirectory(libDirectory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(libDirectory)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().endsWith(".tmp"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    /**
     * Bridge-Plugins liegen in {@code lib/}, damit der Node sie ohne eigene
     * Pfadkonfiguration findet; in den Cloud-Classpath gehören sie trotzdem nicht.
     * Sonst liefen ihre mitgelieferten Bibliotheken (eigene SLF4J-Bindung, Netty,
     * Kryo) in der Cloud mit und würden dort z. B. das Logging umlenken (§12.2).
     *
     * @param jar Jar aus {@code lib/}
     * @return {@code true}, wenn das Jar in den Cloud-Classpath gehört
     */
    public static boolean isCloudJar(Path jar) {
        return !jar.getFileName().toString().startsWith("bridge-");
    }

    /**
     * @param className vollqualifizierter Klassenname
     * @return {@code true}, wenn die Klasse immer vom Parent geladen wird
     */
    public static boolean isShared(String className) {
        return SHARED_PREFIXES.stream().anyMatch(className::startsWith);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = isShared(name) ? null : findInLib(name);
                if (loaded == null) {
                    loaded = super.loadClass(name, false);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private Class<?> findInLib(String name) {
        try {
            return findClass(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "LibClassLoader[" + getURLs().length + " Jars]";
    }
}
