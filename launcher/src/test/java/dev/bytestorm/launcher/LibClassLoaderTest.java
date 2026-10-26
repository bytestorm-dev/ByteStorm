package dev.bytestorm.launcher;

import dev.bytestorm.api.service.PortRange;
import dev.bytestorm.core.config.CloudConfig;
import dev.bytestorm.launcher.layout.CloudLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibClassLoaderTest {

    @TempDir
    Path temp;

    @Test
    void discoversOnlyJarsSortedByName() throws IOException {
        Path lib = temp.resolve("lib");
        Files.createDirectories(lib);
        Files.createFile(lib.resolve("b.jar"));
        Files.createFile(lib.resolve("a.jar"));
        Files.createFile(lib.resolve("notizen.txt"));
        Files.createFile(lib.resolve("c.jar.tmp"));
        Files.createDirectories(lib.resolve("unterordner.jar"));

        List<Path> jars = LibClassLoader.jars(lib);

        assertEquals(List.of("a.jar", "b.jar"),
                jars.stream().map(path -> path.getFileName().toString()).toList());
    }

    @Test
    void missingLibDirectoryYieldsAnEmptyLoader() throws Exception {
        try (LibClassLoader loader = LibClassLoader.open(temp.resolve("fehlt"))) {
            assertEquals(0, loader.getURLs().length);
            assertSame(String.class, loader.loadClass("java.lang.String"));
        }
    }

    @Test
    void loadsApplicationClassesFromLibBeforeParent() throws Exception {
        Path lib = temp.resolve("lib");
        jarClass(lib, "cloud-layout.jar", CloudLayout.class);

        try (LibClassLoader loader = LibClassLoader.open(lib)) {
            Class<?> loaded = loader.loadClass(CloudLayout.class.getName());

            assertEquals(CloudLayout.class.getName(), loaded.getName());
            assertNotSame(CloudLayout.class, loaded, "Child-First muss die Kopie aus lib/ laden");
            assertSame(loader, loaded.getClassLoader());
        }
    }

    @Test
    void loadsTheBootstrapFromLib() throws Exception {
        Path lib = temp.resolve("lib");
        jarClass(lib, "bootstrap.jar", Bootstrap.class);

        try (LibClassLoader loader = LibClassLoader.open(lib)) {
            Class<?> loaded = loader.loadClass(Launcher.BOOTSTRAP_CLASS);

            assertNotSame(Bootstrap.class, loaded);
            assertSame(loader, loaded.getClassLoader());
        }
    }

    @Test
    void sharedPackagesAlwaysComeFromTheParent() throws Exception {
        Path lib = temp.resolve("lib");
        jarClass(lib, "api.jar", PortRange.class);

        try (LibClassLoader loader = LibClassLoader.open(lib)) {
            assertSame(PortRange.class, loader.loadClass(PortRange.class.getName()),
                    "api muss dieselbe Klasse bleiben, sonst brechen Casts zwischen Cloud und Modul");
            assertSame(String.class, loader.loadClass(String.class.getName()));
        }
    }

    @Test
    void fallsBackToTheParentForMissingClasses() throws Exception {
        Path lib = temp.resolve("lib");
        jarClass(lib, "unrelated.jar", LibClassLoaderTest.class);

        try (LibClassLoader loader = LibClassLoader.open(lib)) {
            assertSame(CloudConfig.class, loader.loadClass(CloudConfig.class.getName()));
        }
    }

    @Test
    void keepsBridgePluginsOutOfTheCloudClasspath() throws Exception {
        Path lib = temp.resolve("lib");
        jarClass(lib, "bridge-paper.jar", Bootstrap.class);
        jarClass(lib, "en2do.jar", CloudConfig.class);

        try (LibClassLoader loader = LibClassLoader.open(lib)) {
            assertEquals(1, loader.getURLs().length, "Nur das Cloud-Jar gehört in den Classpath");
            assertFalse(LibClassLoader.isCloudJar(lib.resolve("bridge-paper.jar")));
            assertTrue(LibClassLoader.isCloudJar(lib.resolve("en2do.jar")));
            // Die Klasse kommt aus dem Parent, nicht aus der Bridge-Jar.
            assertSame(Bootstrap.class, loader.loadClass(Bootstrap.class.getName()));
        }
    }

    @Test
    void classifiesSharedPackages() {
        assertTrue(LibClassLoader.isShared("dev.bytestorm.api.ByteStorm"));
        assertTrue(LibClassLoader.isShared("dev.bytestorm.proto.ServiceInfo"));
        assertTrue(LibClassLoader.isShared("com.google.protobuf.Message"));
        assertTrue(LibClassLoader.isShared("org.slf4j.Logger"));
        assertFalse(LibClassLoader.isShared("dev.bytestorm.core.config.CloudConfig"));
        assertFalse(LibClassLoader.isShared("net.dv8tion.jda.api.JDA"));
    }

    private static void jarClass(Path libDirectory, String jarName, Class<?> type) throws IOException {
        String entryName = type.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = type.getClassLoader().getResourceAsStream(entryName)) {
            assertNotNull(in, "Klassendatei fehlt im Test-Classpath: " + entryName);
            bytes = in.readAllBytes();
        }

        Files.createDirectories(libDirectory);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(libDirectory.resolve(jarName)))) {
            out.putNextEntry(new JarEntry(entryName));
            out.write(bytes);
            out.closeEntry();
        }
    }
}
