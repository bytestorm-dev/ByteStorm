package dev.bytestorm.core.update;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterTest {

    @TempDir
    Path temp;

    private final List<String> requests = new ArrayList<>();

    private HttpServer server;
    private Path cloud;
    private Updater updater;
    private byte[] distribution;

    @BeforeEach
    void setUp() throws IOException {
        distribution = zip(java.util.Map.of(
                "bytestorm.jar", "neu",
                "lib/core-1.0.jar", "neu",
                "lib/.manifest.json", "{}"
        ));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/bytestorm/ByteStorm/releases", exchange -> {
            requests.add("releases");
            byte[] body = releases(baseUrl()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            respond(exchange, 200, body);
        });
        server.createContext("/asset.zip", exchange -> {
            requests.add("asset");
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            respond(exchange, 200, distribution);
        });
        server.start();

        cloud = Files.createDirectories(temp.resolve("cloud"));
        Files.createDirectories(cloud.resolve("lib"));
        Files.writeString(cloud.resolve("bytestorm.jar"), "alt");
        Files.writeString(cloud.resolve("lib/core-1.0.jar"), "alt");
        updater = new Updater(cloud, "bytestorm/ByteStorm",
                URI.create(baseUrl()), HttpClient.newBuilder().build());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void releasesAreSortedAndDraftsAreSkipped() {
        List<Updater.Release> releases = updater.releases();

        assertEquals(2, releases.size());
        assertEquals("v1.2-SNAPSHOT", releases.get(0).tag(), "neueste zuerst");
        assertTrue(releases.get(0).prerelease());
        Updater.Release stable = releases.get(1);
        assertEquals("v1.1", stable.tag());
        assertEquals("1.1", stable.version());
        assertFalse(stable.prerelease());
        assertEquals("bytestorm-1.1.zip", stable.assetName());
        assertTrue(stable.installable());
    }

    @Test
    void latestSkipsPrereleasesAndByVersionAcceptsBothForms() {
        assertEquals("v1.1", updater.latest().orElseThrow().tag());
        assertTrue(updater.byVersion("1.1").isPresent());
        assertTrue(updater.byVersion("v1.1").isPresent());
        assertTrue(updater.byVersion("9.9").isEmpty());
    }

    @Test
    void newerThanComparesWithTheRunningVersion() {
        assertEquals("v1.1", updater.newerThan("1.0").orElseThrow().tag());
        assertTrue(updater.newerThan("1.1").isEmpty(), "gleiche Version ist kein Update");
        assertTrue(updater.newerThan("2.0-SNAPSHOT").isEmpty(),
                "ein neuerer Entwicklungsstand wird nicht als veraltet gemeldet");
        assertTrue(updater.newerThan("").isEmpty());
    }

    @Test
    void installReplacesTheDistributionAndKeepsABackup() throws IOException {
        int files = updater.install(updater.latest().orElseThrow());

        assertEquals(3, files, "Einstiegs-Jar, Jar im lib/ und das Manifest");
        assertEquals("neu", Files.readString(cloud.resolve("bytestorm.jar")));
        assertEquals("neu", Files.readString(cloud.resolve("lib/core-1.0.jar")));
        assertEquals("alt", Files.readString(cloud.resolve("update/backup/bytestorm.jar")));
        assertEquals("alt", Files.readString(cloud.resolve("update/backup/lib/core-1.0.jar")));
        assertTrue(requests.contains("asset"));
    }

    @Test
    void installRemovesTheJarsOfTheOldRelease() throws IOException {
        Files.writeString(cloud.resolve("lib/cli-1.1.0.jar"), "alt");
        Files.writeString(cloud.resolve("lib/module-custom.jar"), "eigen");
        distribution = zip(java.util.Map.of(
                "bytestorm.jar", "neu",
                "lib/cli-1.2.0.jar", "neu",
                "lib/dependencies.txt", "neu"
        ));

        updater.install(updater.latest().orElseThrow());

        assertFalse(Files.exists(cloud.resolve("lib/cli-1.1.0.jar")),
                "die alte Fassung darf nicht neben der neuen liegen bleiben");
        assertEquals("alt", Files.readString(cloud.resolve("update/backup/lib/cli-1.1.0.jar")));
        assertTrue(Files.exists(cloud.resolve("lib/module-custom.jar")),
                "eigene Module unter lib/ bleiben unangetastet");
        assertEquals("neu", Files.readString(cloud.resolve("lib/cli-1.2.0.jar")));
        assertEquals("neu", Files.readString(cloud.resolve("lib/dependencies.txt")),
                "die Liste der Fremd-Jars gehört zum Update");
    }

    @Test
    void installBringsTheServerPluginsWithIt() throws IOException {
        distribution = zip(java.util.Map.of(
                "bytestorm.jar", "neu",
                "lib/core-1.0.jar", "neu",
                "plugins/bytestorm-lobby.jar", "lobby",
                "plugins/bytestorm-proxy.jar", "proxy",
                "plugins/notes.txt", "kein Jar"
        ));

        int files = updater.install(updater.latest().orElseThrow());

        assertEquals(4, files, "Einstiegs-Jar, Jar im lib/ und beide Server-Plugins");
        assertEquals("lobby", Files.readString(cloud.resolve("plugins/bytestorm-lobby.jar")));
        assertEquals("proxy", Files.readString(cloud.resolve("plugins/bytestorm-proxy.jar")));
        assertFalse(Files.exists(cloud.resolve("plugins/notes.txt")),
                "nur Jars gehören nach plugins/");
    }

    @Test
    void installReplacesAnOlderPluginJarAndKeepsABackup() throws IOException {
        Files.createDirectories(cloud.resolve("plugins"));
        Files.writeString(cloud.resolve("plugins/bytestorm-lobby.jar"), "alt");
        distribution = zip(java.util.Map.of(
                "bytestorm.jar", "neu",
                "lib/core-1.0.jar", "neu",
                "plugins/bytestorm-lobby.jar", "neu"
        ));

        updater.install(updater.latest().orElseThrow());

        assertEquals("neu", Files.readString(cloud.resolve("plugins/bytestorm-lobby.jar")));
        assertEquals("alt",
                Files.readString(cloud.resolve("update/backup/plugins/bytestorm-lobby.jar")));
    }

    @Test
    void installRejectsAnArchiveWithoutTheLauncherJar() throws IOException {
        distribution = zip(java.util.Map.of("lib/core-1.0.jar", "neu"));

        UpdateException error = assertThrows(UpdateException.class,
                () -> updater.install(updater.latest().orElseThrow()));

        assertTrue(error.getMessage().contains("bytestorm.jar"), error.getMessage());
        assertEquals("alt", Files.readString(cloud.resolve("bytestorm.jar")), "nichts ersetzt");
    }

    @Test
    void installRejectsPathsOutsideTheCloudDirectory() throws IOException {
        distribution = zip(java.util.Map.of(
                "bytestorm.jar", "neu",
                "lib/../../entwischt.jar", "böse"
        ));

        UpdateException error = assertThrows(UpdateException.class,
                () -> updater.install(updater.latest().orElseThrow()));

        assertTrue(error.getMessage().contains("unzulässigen Pfad"), error.getMessage());
        assertFalse(Files.exists(temp.resolve("entwischt.jar")));
    }

    @Test
    void unknownRepositoryIsReportedClearly() {
        Updater unbekannt = new Updater(cloud, "bytestorm/Unbekannt",
                URI.create(baseUrl()), HttpClient.newBuilder().build());

        UpdateException error = assertThrows(UpdateException.class, unbekannt::releases);

        assertTrue(error.getMessage().contains("nicht gefunden"), error.getMessage());
    }

    @Test
    void releasesWithoutZipAreNotInstallable() {
        Updater ohneArtefakt = new Updater(cloud, "bytestorm/ByteStorm",
                URI.create(baseUrl()), HttpClient.newBuilder().build());
        // Das Release v1.2-SNAPSHOT trägt kein Zip.
        Updater.Release beta = ohneArtefakt.releases().get(0);

        assertFalse(beta.installable());
        assertThrows(UpdateException.class, () -> ohneArtefakt.install(beta));
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
                    "html_url": "https://example.test/v1.2-SNAPSHOT",
                    "assets": []
                  },
                  {
                    "tag_name": "v1.1",
                    "name": "ByteStorm 1.1",
                    "prerelease": false,
                    "draft": false,
                    "published_at": "2026-10-20T09:00:00Z",
                    "html_url": "https://example.test/v1.1",
                    "assets": [
                      {"name": "bytestorm-1.1.zip", "browser_download_url": "%s/asset.zip"}
                    ]
                  },
                  {
                    "tag_name": "v0.9",
                    "name": "Entwurf",
                    "prerelease": false,
                    "draft": true,
                    "published_at": "2026-10-01T09:00:00Z",
                    "assets": []
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

    private static byte[] zip(java.util.Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (java.util.Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
