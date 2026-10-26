package dev.bytestorm.launcher.platform;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformDownloaderTest {

    private static final byte[] JAR = "fake-paper-jar".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temp;

    private final AtomicInteger downloads = new AtomicInteger();
    private final AtomicReference<String> lastUserAgent = new AtomicReference<>();

    private HttpServer server;
    private PlatformDownloader downloader;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        downloader = new PlatformDownloader(temp.resolve("platforms"), base);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void downloadsAStableBuildAndReusesIt() throws IOException {
        Path jar = downloader.ensure("paper", "1.20.1");

        assertEquals(temp.resolve("platforms").resolve("paper-1.20.1.jar"), jar);
        assertArrayEquals(JAR, Files.readAllBytes(jar));
        assertEquals(1, downloads.get());
        assertTrue(lastUserAgent.get().contains("ByteStorm"), lastUserAgent.get());

        downloader.ensure("paper", "1.20.1");

        assertEquals(1, downloads.get(), "geprüfte Jar darf nicht erneut geladen werden");
    }

    @Test
    void resolvesTheNewestVersionWithAStableBuild() {
        Path jar = downloader.ensure("paper", null);

        assertEquals(temp.resolve("platforms").resolve("paper-1.20.1.jar"), jar);
        assertEquals(1, downloads.get());
    }

    @Test
    void rejectsAWrongChecksumAndLeavesNothingBehind() throws IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/builds")) {
                String base = base(exchange);
                send(exchange, 200, "[" + build(base, "falsche-pruefsumme", "STABLE") + "]");
            } else if (path.startsWith("/files/")) {
                send(exchange, 200, JAR);
            } else {
                send(exchange, 404, "");
            }
        });
        server.start();
        downloader = new PlatformDownloader(temp.resolve("platforms"),
                URI.create(base(server.getAddress().getPort())));

        PlatformDownloadException error = assertThrows(PlatformDownloadException.class,
                () -> downloader.ensure("paper", "1.20.1"));

        assertTrue(error.getMessage().contains("Prüfsumme falsch"), error.getMessage());
        assertFalse(Files.exists(temp.resolve("platforms").resolve("paper-1.20.1.jar")));
        assertFalse(hasTempFile(temp.resolve("platforms")));
    }

    @Test
    void reportsMissingStableBuilds() {
        PlatformDownloadException error = assertThrows(PlatformDownloadException.class,
                () -> downloader.ensure("paper", "1.19.4"));

        assertTrue(error.getMessage().contains("Kein stabiler Build"), error.getMessage());
    }

    @Test
    void reportsApiErrorsAndRejectsUnsafeNames() {
        PlatformDownloadException api = assertThrows(PlatformDownloadException.class,
                () -> downloader.ensure("missing", "1.20.1"));
        assertTrue(api.getMessage().contains("PaperMC-API"), api.getMessage());

        assertThrows(PlatformDownloadException.class, () -> downloader.ensure("../paper", "1.20.1"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
        if (lastUserAgent.get() == null || !lastUserAgent.get().contains("ByteStorm")) {
            send(exchange, 400, "User-Agent fehlt");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/v3/projects/paper")) {
            send(exchange, 200, """
                    {"versions":{"1.21":["1.21.1"],"1.20":["1.20.1"]}}
                    """);
            return;
        }
        if (path.equals("/v3/projects/paper/versions/1.21.1/builds")) {
            send(exchange, 200, "[]");
            return;
        }
        if (path.equals("/v3/projects/paper/versions/1.20.1/builds")) {
            send(exchange, 200, "[" + build(base(exchange), checksum(), "STABLE") + "]");
            return;
        }
        if (path.equals("/v3/projects/paper/versions/1.19.4/builds")) {
            send(exchange, 200, "[]");
            return;
        }
        if (path.equals("/v3/projects/missing")
                || path.equals("/v3/projects/missing/versions/1.20.1/builds")) {
            send(exchange, 200, "{\"ok\":false,\"message\":\"Projekt unbekannt\"}");
            return;
        }
        if (path.equals("/files/paper-1.20.1.jar")) {
            downloads.incrementAndGet();
            send(exchange, 200, JAR);
            return;
        }
        send(exchange, 404, "");
    }

    private static String build(String base, String sha256, String channel) {
        return """
                {"id":42,"channel":"%s","downloads":{"server:default":{
                  "name":"paper-1.20.1-42.jar",
                  "url":"%s/files/paper-1.20.1.jar",
                  "checksums":{"sha256":"%s"},
                  "size":%d
                }}}
                """.formatted(channel, base, sha256, JAR.length);
    }

    private static String base(HttpExchange exchange) {
        return base(exchange.getLocalAddress().getPort());
    }

    private static String base(int port) {
        return "http://127.0.0.1:" + port;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static boolean hasTempFile(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp"));
        } catch (IOException e) {
            return false;
        }
    }

    private static String checksum() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JAR));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
