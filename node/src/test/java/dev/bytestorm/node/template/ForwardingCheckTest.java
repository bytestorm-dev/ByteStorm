package dev.bytestorm.node.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForwardingCheckTest {

    @TempDir
    Path root;

    @Test
    void matchingModernForwardingProducesNoFindings() throws IOException {
        Path proxy = proxyDir("modern", "gemeinsam");
        Path backend = backendDir(true, "gemeinsam");

        List<ForwardingCheck.Finding> findings = ForwardingCheck.compare(
                Map.of("Proxy-1", proxy), Map.of("Lobby-1", backend), "gemeinsam");

        assertEquals(List.of(), findings);
    }

    @Test
    void reportsForwardingModeThatIsNotModern() throws IOException {
        Path proxy = proxyDir("none", "gemeinsam");

        List<ForwardingCheck.Finding> findings = ForwardingCheck.compare(
                Map.of("Proxy-1", proxy), Map.of(), "gemeinsam");

        assertTrue(findings.stream().anyMatch(finding -> finding.severity() == ForwardingCheck.Severity.ERROR
                && finding.service().equals("Proxy-1")
                && finding.message().contains("'none'")), findings.toString());
    }

    @Test
    void reportsSecretsThatDifferOnBothSides() throws IOException {
        Path proxy = proxyDir("modern", "proxy-secret");
        Path backend = backendDir(true, "backend-secret");

        List<ForwardingCheck.Finding> findings = ForwardingCheck.compare(
                Map.of("Proxy-1", proxy), Map.of("Lobby-1", backend), "cloud-secret");

        assertTrue(findings.stream().anyMatch(finding -> finding.service().equals("Proxy-1")
                && finding.message().contains("config/cloud.json")), findings.toString());
        assertTrue(findings.stream().anyMatch(finding -> finding.service().equals("Lobby-1")
                && finding.message().contains("weicht")), findings.toString());
    }

    @Test
    void reportsDisabledVelocityOnBackend() throws IOException {
        Path backend = backendDir(false, "gemeinsam");

        List<ForwardingCheck.Finding> findings = ForwardingCheck.compare(
                Map.of(), Map.of("Lobby-1", backend), "gemeinsam");

        assertTrue(findings.stream().anyMatch(finding -> finding.severity() == ForwardingCheck.Severity.ERROR
                && finding.message().contains("proxies.velocity.enabled")), findings.toString());
    }

    @Test
    void missingSecretFileIsAnError() throws IOException {
        Path proxy = root.resolve("Proxy-1");
        Files.createDirectories(proxy);
        Files.writeString(proxy.resolve("velocity.toml"), """
                player-info-forwarding-mode = "modern"
                forwarding-secret-file = "forwarding.secret"
                """);

        List<ForwardingCheck.Finding> findings = ForwardingCheck.compare(
                Map.of("Proxy-1", proxy), Map.of(), "gemeinsam");

        assertTrue(findings.stream().anyMatch(finding -> finding.message().contains("Secret-Datei 'forwarding.secret' fehlt")),
                findings.toString());
    }

    @Test
    void readsServerEntriesFromVelocityToml() throws IOException {
        Path proxy = root.resolve("Proxy-1");
        Files.createDirectories(proxy);
        Files.writeString(proxy.resolve("velocity.toml"), """
                bind = "0.0.0.0:25565"
                player-info-forwarding-mode = "modern"

                [servers]
                lobby = "127.0.0.1:30001"
                Lobby-1 = "127.0.0.1:30001"
                try = ["lobby"]

                [forced-hosts]
                """);

        assertEquals(List.of(
                new ForwardingCheck.ServerEntry("lobby", "127.0.0.1:30001"),
                new ForwardingCheck.ServerEntry("Lobby-1", "127.0.0.1:30001")),
                ForwardingCheck.readServers(proxy));
    }

    private Path proxyDir(String mode, String secret) throws IOException {
        Path directory = root.resolve("Proxy-1");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("velocity.toml"), """
                config-version = "2.9"
                player-info-forwarding-mode = "%s"
                forwarding-secret-file = "forwarding.secret"

                [servers]
                bytestorm-placeholder = "127.0.0.1:1"
                try = ["bytestorm-placeholder"]
                """.formatted(mode));
        Files.writeString(directory.resolve("forwarding.secret"), secret + "\n");
        return directory;
    }

    private Path backendDir(boolean enabled, String secret) throws IOException {
        Path directory = root.resolve("Lobby-1");
        Files.createDirectories(directory.resolve("config"));
        Files.writeString(directory.resolve(ForwardingCheck.PAPER_CONFIG), """
                proxies:
                  velocity:
                    enabled: %s
                    online-mode: true
                    secret: '%s'
                """.formatted(enabled, secret));
        return directory;
    }
}
