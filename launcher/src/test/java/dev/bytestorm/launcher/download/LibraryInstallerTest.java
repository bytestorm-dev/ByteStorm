package dev.bytestorm.launcher.download;

import com.sun.net.httpserver.HttpServer;
import dev.bytestorm.launcher.layout.CloudLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryInstallerTest {

    private static final byte[] FIRST = "erste-jar".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SECOND = "zweite-jar".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temp;

    private final Map<String, byte[]> served = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private ExecutorService handlerExecutor;
    private HttpServer server;
    private CloudLayout layout;
    private URI repository;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                requests.incrementAndGet();
                byte[] body = served.get(exchange.getRequestURI().getPath());
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } finally {
                exchange.close();
            }
        });
        handlerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlerExecutor);
        server.start();

        layout = new CloudLayout(temp.resolve("cloud"));
        Files.createDirectories(layout.lib());
        repository = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        handlerExecutor.shutdownNow();
    }

    @Test
    void downloadsMissingLibrariesAndWritesTheManifest() throws IOException {
        Dependency first = dependency("first", "1.0", FIRST);
        Dependency second = dependency("second", "2.0", SECOND);
        served.put("/" + first.mavenPath(), FIRST);
        served.put("/" + second.mavenPath(), SECOND);
        writeList(first, second);

        LibraryInstaller.Result result = install();

        assertTrue(result.complete(), result.missing().toString());
        assertEquals(2, result.checked());
        assertEquals(2, result.downloaded());
        assertArrayEquals(FIRST, Files.readAllBytes(layout.lib().resolve(first.fileName())));
        assertArrayEquals(SECOND, Files.readAllBytes(layout.lib().resolve(second.fileName())));
        assertFalse(Files.exists(layout.lib().resolve(first.fileName() + ".tmp")));

        LibManifest manifest = LibManifest.read(layout.lib());
        assertEquals(List.of(first.fileName(), second.fileName()),
                manifest.dependencies().stream().map(LibManifest.Entry::file).toList());
        assertEquals(FIRST.length, manifest.dependencies().get(0).bytes());
        assertEquals(first.coordinates(), manifest.dependencies().get(0).coordinates());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("2 geladen"),
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void withoutAListNothingHappens() {
        LibraryInstaller.Result result = install();

        assertTrue(result.complete());
        assertEquals(0, result.checked());
        assertEquals(0, requests.get());
    }

    @Test
    void alreadyVerifiedFilesAreNotDownloadedAgain() throws IOException {
        Dependency first = dependency("first", "1.0", FIRST);
        served.put("/" + first.mavenPath(), FIRST);
        writeList(first);

        install();
        int requestsAfterFirstRun = requests.get();
        LibraryInstaller.Result second = install();

        assertEquals(requestsAfterFirstRun, requests.get(), "eine geprüfte Datei bleibt liegen");
        assertEquals(0, second.downloaded());
        assertEquals(1, second.checked());
    }

    @Test
    void damagedFilesAreReplaced() throws IOException {
        Dependency first = dependency("first", "1.0", FIRST);
        served.put("/" + first.mavenPath(), FIRST);
        writeList(first);
        install();
        Files.writeString(layout.lib().resolve(first.fileName()), "kaputt");

        LibraryInstaller.Result result = install();

        assertTrue(result.complete());
        assertEquals(1, result.downloaded());
        assertArrayEquals(FIRST, Files.readAllBytes(layout.lib().resolve(first.fileName())));
    }

    @Test
    void librariesFromThePreviousManifestAreRemovedAfterAnUpdate() throws IOException {
        Dependency first = dependency("first", "1.0", FIRST);
        served.put("/" + first.mavenPath(), FIRST);
        writeList(first);
        install();

        Dependency second = dependency("second", "2.0", SECOND);
        served.put("/" + second.mavenPath(), SECOND);
        writeList(second);
        LibraryInstaller.Result result = install();

        assertTrue(result.complete());
        assertFalse(Files.exists(layout.lib().resolve(first.fileName())),
                "die alte Fassung darf nicht neben der neuen liegen");
        assertArrayEquals(SECOND, Files.readAllBytes(layout.lib().resolve(second.fileName())));
        assertEquals(List.of(second.fileName()), LibManifest.read(layout.lib()).dependencies().stream()
                .map(LibManifest.Entry::file).toList());
    }

    @Test
    void manuallyPlacedJarsSurviveTheCleanup() throws IOException {
        Path manual = layout.lib().resolve("eigenes-plugin.jar");
        Dependency first = dependency("first", "1.0", FIRST);
        served.put("/" + first.mavenPath(), FIRST);
        writeList(first);
        install();

        Files.writeString(manual, "selbst abgelegt");
        install();

        assertTrue(Files.exists(manual), "nur das Manifest wird aufgeräumt");
    }

    @Test
    void reportsMissingLibrariesWithoutDroppingTheOthers() throws IOException {
        Dependency first = dependency("first", "1.0", FIRST);
        Dependency second = dependency("second", "2.0", SECOND);
        served.put("/" + first.mavenPath(), FIRST);
        writeList(first, second);

        LibraryInstaller.Result result = install();

        assertFalse(result.complete());
        assertEquals(1, result.missing().size());
        LibraryInstaller.Missing missing = result.missing().get(0);
        assertEquals(second.coordinates(), missing.coordinates());
        assertEquals(layout.lib().resolve(second.fileName()), missing.target());
        assertEquals(second.sha256(), missing.sha256());
        assertTrue(missing.reason().contains("404"), missing.reason());
        assertTrue(missing.describe().contains("Ziel:"), missing.describe());
        assertTrue(missing.describe().contains("SHA-256: " + second.sha256()), missing.describe());

        assertArrayEquals(FIRST, Files.readAllBytes(layout.lib().resolve(first.fileName())));
        assertFalse(Files.exists(layout.lib().resolve(LibManifest.FILE_NAME)),
                "bei fehlenden Bibliotheken entsteht kein halbes Manifest");
    }

    @Test
    void readsBothTheLongAndTheShortLineFormat() throws IOException {
        Path list = layout.lib().resolve(LibraryInstaller.LIST_FILE);
        Files.writeString(list, """
                # Kommentar
                com.example:first:1.0:%s

                com.example:second:2.0::%s
                """.formatted(sha256(FIRST), sha256(SECOND)));

        List<Dependency> dependencies = LibraryInstaller.read(list);

        assertEquals(2, dependencies.size());
        assertEquals("first", dependencies.get(0).artifactId());
        assertNull(dependencies.get(0).classifier());
        assertNull(dependencies.get(1).classifier());
        assertEquals("first-1.0.jar", dependencies.get(0).fileName());
    }

    @Test
    void aMalformedLineIsReported() throws IOException {
        Path list = layout.lib().resolve(LibraryInstaller.LIST_FILE);
        Files.writeString(list, "com.example:first\n");

        DownloadException error = assertThrows(DownloadException.class, () -> LibraryInstaller.read(list));
        assertTrue(error.getMessage().contains("Zeile 1"), error.getMessage());
    }

    private LibraryInstaller.Result install() {
        return LibraryInstaller.install(layout, repository, new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    private void writeList(Dependency... dependencies) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Dependency dependency : dependencies) {
            text.append(dependency.groupId()).append(':')
                    .append(dependency.artifactId()).append(':')
                    .append(dependency.version()).append(':')
                    .append(dependency.classifier() == null ? "" : dependency.classifier()).append(':')
                    .append(dependency.sha256()).append('\n');
        }
        Files.writeString(layout.lib().resolve(LibraryInstaller.LIST_FILE), text);
    }

    private static Dependency dependency(String artifactId, String version, byte[] content) {
        return new Dependency("com.example", artifactId, version, sha256(content));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", e);
        }
    }
}
