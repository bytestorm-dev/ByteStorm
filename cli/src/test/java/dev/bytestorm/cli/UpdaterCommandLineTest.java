package dev.bytestorm.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.bytestorm.core.update.Updater;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterCommandLineTest {

    @TempDir
    Path temp;

    private HttpServer server;
    private Path cloud;
    private Updater updater;

    @BeforeEach
    void setUp() throws IOException {
        byte[] distribution = zip(Map.of("bytestorm.jar", "neu", "lib/core-1.0.jar", "neu"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/bytestorm/ByteStorm/releases", exchange ->
                respond(exchange, 200, releases(baseUrl()).getBytes(StandardCharsets.UTF_8)));
        server.createContext("/asset.zip", exchange -> respond(exchange, 200, distribution));
        server.start();

        cloud = Files.createDirectories(temp.resolve("cloud"));
        Files.createDirectories(cloud.resolve("lib"));
        Files.writeString(cloud.resolve("bytestorm.jar"), "alt");
        updater = new Updater(cloud, "bytestorm/ByteStorm",
                URI.create(baseUrl()), HttpClient.newBuilder().build());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void versionsHideBetasUntilAllIsAskedFor() {
        UpdaterCommandLine commandLine = commandLine("1.1");

        String stable = commandLine.execute(new String[]{"updater", "versions"});
        assertTrue(stable.contains("v1.1\tRelease\t2026-10-20\tbytestorm-1.1.zip"), stable);
        assertTrue(stable.contains("--all"), stable);
        assertTrue(stable.contains("versions --all"), stable);

        String all = commandLine.execute(new String[]{"updater", "versions", "--all"});
        assertTrue(all.contains("v1.2-SNAPSHOT\tBeta"), all);
    }

    @Test
    void checkKnowsWhetherTheRunningVersionIsCurrent() {
        assertTrue(commandLine("1.1").execute(new String[]{"updater", "check"}).contains("Aktuell: 1.1"));

        String old = commandLine("1.0").execute(new String[]{"updater", "check"});
        assertTrue(old.contains("Update verfügbar: 1.0 → v1.1"), old);
        assertTrue(old.contains("updater update"), old);
    }

    @Test
    void checkWithoutKnownVersionSaysSo() {
        UpdaterCommandLine commandLine = new UpdaterCommandLine(updater, Optional::empty);

        String output = commandLine.execute(new String[]{"updater", "check"});

        assertTrue(output.contains("eigene Version unbekannt"), output);
    }

    @Test
    void updateInstallsTheNewestReleaseAndAsksForARestart() throws IOException {
        String output = commandLine("1.0").execute(new String[]{"updater", "update"});

        assertTrue(output.startsWith("Update eingespielt: v1.1 (2 Dateien"), output);
        assertTrue(output.contains("Neustart nötig"), output);
        assertEquals("neu", Files.readString(cloud.resolve("bytestorm.jar")));
    }

    @Test
    void updateWithAnUnknownVersionReportsIt() {
        String output = commandLine("1.0").execute(new String[]{"updater", "update", "9.9"});

        assertTrue(output.contains("Kein passendes Release gefunden: 9.9"), output);
    }

    @Test
    void repositoryShowsSourceAndRunningVersion() {
        String output = commandLine("1.0").execute(new String[]{"updater", "repository"});

        assertTrue(output.contains("Repository: bytestorm/ByteStorm"), output);
        assertTrue(output.contains("Laufende Version: 1.0"), output);
    }

    @Test
    void helpAndUnknownCommand() {
        UpdaterCommandLine commandLine = commandLine("1.0");

        assertTrue(commandLine.execute(new String[]{"updater", "help"}).contains("updater update"));
        assertTrue(commandLine.execute(new String[]{"updater", "quark"}).contains("Unbekanntes Updater-Kommando"));
    }

    private UpdaterCommandLine commandLine(String version) {
        return new UpdaterCommandLine(updater, () -> Optional.of(version));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String releases(String base) {
        return """
                [
                  {
                    "tag_name": "v1.2-SNAPSHOT",
                    "name": "ByteStorm 1.2-SNAPSHOT (Beta)",
                    "prerelease": true,
                    "draft": false,
                    "published_at": "2026-10-24T09:00:00Z",
                    "assets": []
                  },
                  {
                    "tag_name": "v1.1",
                    "name": "ByteStorm 1.1",
                    "prerelease": false,
                    "draft": false,
                    "published_at": "2026-10-20T09:00:00Z",
                    "assets": [
                      {"name": "bytestorm-1.1.zip", "browser_download_url": "%s/asset.zip"}
                    ]
                  }
                ]
                """.formatted(base);
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
