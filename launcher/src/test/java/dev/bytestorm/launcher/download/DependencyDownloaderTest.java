package dev.bytestorm.launcher.download;

import com.sun.net.httpserver.HttpServer;
import dev.bytestorm.launcher.layout.CloudLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DependencyDownloaderTest {

    private static final byte[] JAR = "fake-jar-content".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temp;

    private final Map<String, byte[]> served = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile long delayMillis;
    private volatile CountDownLatch simultaneous;

    private ExecutorService handlerExecutor;
    private HttpServer server;
    private CloudLayout layout;
    private DependencyDownloader downloader;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                requests.incrementAndGet();
                CountDownLatch rendezvous = simultaneous;
                if (rendezvous != null) {
                    rendezvous.countDown();
                    try {
                        if (!rendezvous.await(5, TimeUnit.SECONDS)) {
                            exchange.sendResponseHeaders(503, -1);
                            return;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        exchange.sendResponseHeaders(503, -1);
                        return;
                    }
                }
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
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
        downloader = new DependencyDownloader(layout,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        handlerExecutor.shutdownNow();
    }

    @Test
    void downloadsMissingJarAndWritesManifest() throws IOException {
        Dependency dependency = dependency(JAR);
        served.put("/" + dependency.mavenPath(), JAR);

        LibManifest manifest = downloader.ensure(List.of(dependency));

        Path jar = layout.lib().resolve(dependency.fileName());
        assertArrayEquals(JAR, Files.readAllBytes(jar));
        assertFalse(Files.exists(layout.lib().resolve(dependency.fileName() + ".tmp")));

        assertEquals(1, manifest.dependencies().size());
        LibManifest.Entry entry = manifest.dependencies().get(0);
        assertEquals(dependency.fileName(), entry.file());
        assertEquals(dependency.sha256(), entry.sha256());
        assertEquals(JAR.length, entry.bytes());
        assertEquals(entry, LibManifest.read(layout.lib()).find(dependency.fileName()).orElseThrow());
        assertEquals(1, requests.get());
    }

    @Test
    void reusesAnAlreadyVerifiedJarWithoutRequest() {
        Dependency dependency = dependency(JAR);
        served.put("/" + dependency.mavenPath(), JAR);
        downloader.ensure(List.of(dependency));
        int before = requests.get();

        LibManifest manifest = downloader.ensure(List.of(dependency));

        assertEquals(before, requests.get(), "geprüfte Datei darf nicht erneut geladen werden");
        assertEquals(1, manifest.dependencies().size());
    }

    @Test
    void replacesAJarWithAWrongChecksum() throws IOException {
        Dependency dependency = dependency(JAR);
        served.put("/" + dependency.mavenPath(), JAR);
        downloader.ensure(List.of(dependency));
        Files.writeString(layout.lib().resolve(dependency.fileName()), "korrupt");

        downloader.ensure(List.of(dependency));

        assertArrayEquals(JAR, Files.readAllBytes(layout.lib().resolve(dependency.fileName())));
        assertEquals(2, requests.get());
    }

    @Test
    void rejectsAWrongChecksumAndLeavesNothingBehind() {
        Dependency dependency = dependency(JAR);
        served.put("/" + dependency.mavenPath(), "manipuliert".getBytes(StandardCharsets.UTF_8));

        DownloadException error = assertThrows(DownloadException.class,
                () -> downloader.ensure(List.of(dependency)));

        assertTrue(error.getMessage().contains("Prüfsumme falsch"), error.getMessage());
        assertFalse(Files.exists(layout.lib().resolve(dependency.fileName())));
        assertFalse(Files.exists(layout.lib().resolve(dependency.fileName() + ".tmp")));
        assertFalse(Files.exists(layout.lib().resolve(LibManifest.FILE_NAME)));
    }

    @Test
    void reportsHttpErrors() {
        Dependency dependency = dependency(JAR);

        DownloadException error = assertThrows(DownloadException.class,
                () -> downloader.ensure(List.of(dependency)));

        assertTrue(error.getMessage().contains("404"), error.getMessage());
        assertFalse(error instanceof OfflineException, "404 ist kein Netzwerkausfall");
        assertFalse(Files.exists(layout.lib().resolve(dependency.fileName())));
        assertFalse(Files.exists(layout.lib().resolve(dependency.fileName() + ".tmp")));
    }

    @Test
    void reportsMissingNetworkWithGuidance() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Dependency dependency = dependency(JAR);
        DependencyDownloader offline = new DependencyDownloader(layout,
                URI.create("http://127.0.0.1:" + port));

        OfflineException error = assertThrows(OfflineException.class,
                () -> offline.ensure(List.of(dependency)));

        String message = error.getMessage();
        assertTrue(message.contains("Keine Netzwerkverbindung"), message);
        assertTrue(message.contains(dependency.fileName()), message);
        assertTrue(message.contains(dependency.sha256()), message);
        assertTrue(message.contains(layout.lib().toString()), message);
        assertTrue(message.contains("manuell"), message);
        assertNotNull(error.getCause());
        assertFalse(Files.exists(layout.lib().resolve(dependency.fileName())));
        assertFalse(Files.exists(layout.lib().resolve(dependency.fileName() + ".tmp")));
    }

    @Test
    void downloadsAllDependenciesInParallelWithProgress() {
        Dependency first = new Dependency("com.example", "first", "1.0", sha256(JAR));
        Dependency second = new Dependency("com.example", "second", "2.0", sha256(JAR));
        Dependency third = new Dependency("com.example", "third", "3.0", sha256(JAR));
        for (Dependency dependency : List.of(first, second, third)) {
            served.put("/" + dependency.mavenPath(), JAR);
        }
        // Alle drei Anfragen müssen gleichzeitig eintreffen, sonst antwortet der Server mit 503.
        simultaneous = new CountDownLatch(3);
        List<DownloadStatus> statuses = Collections.synchronizedList(new ArrayList<>());

        LibManifest manifest = downloader.ensure(List.of(first, second, third), statuses::add);

        assertEquals(List.of(first.fileName(), second.fileName(), third.fileName()),
                manifest.dependencies().stream().map(LibManifest.Entry::file).toList(),
                "Manifest behält die Eingabereihenfolge");
        assertEquals(List.of(1, 2, 3), statuses.stream()
                .map(DownloadStatus::completed)
                .sorted()
                .toList());
        assertTrue(statuses.stream().allMatch(DownloadStatus::downloaded));
        assertTrue(statuses.stream().allMatch(status -> status.total() == 3));
        assertEquals(3, requests.get());
    }

    @Test
    void reportsReusedFilesInProgress() {
        Dependency dependency = dependency(JAR);
        served.put("/" + dependency.mavenPath(), JAR);
        downloader.ensure(List.of(dependency));

        List<DownloadStatus> statuses = new ArrayList<>();
        downloader.ensure(List.of(dependency), statuses::add);

        assertEquals(1, statuses.size());
        DownloadStatus status = statuses.get(0);
        assertFalse(status.downloaded());
        assertEquals(1, status.completed());
        assertTrue(status.finished());
    }

    @Test
    void abortsCleanlyWhenInterrupted() throws Exception {
        Dependency dependency = dependency(JAR);
        served.put("/" + dependency.mavenPath(), JAR);
        delayMillis = 2_000;

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                downloader.ensure(List.of(dependency));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "download-test");
        worker.start();
        Thread.sleep(250);
        worker.interrupt();
        worker.join(10_000);

        if (worker.isAlive()) {
            fail("Download reagiert nicht auf Abbruch");
        }
        assertInstanceOf(DownloadException.class, failure.get());
        assertTrue(failure.get().getMessage().contains("abgebrochen"), failure.get().getMessage());
        assertFalse(Files.exists(layout.lib().resolve(dependency.fileName())));
        assertFalse(Files.exists(layout.lib().resolve(dependency.fileName() + ".tmp")));
    }

    private static Dependency dependency(byte[] content) {
        return new Dependency("com.example", "lib", "1.0", sha256(content));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", e);
        }
    }
}
