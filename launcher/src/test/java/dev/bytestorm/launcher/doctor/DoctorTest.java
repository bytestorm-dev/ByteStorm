package dev.bytestorm.launcher.doctor;

import dev.bytestorm.cli.Diagnostics;
import dev.bytestorm.core.config.CloudConfig;
import dev.bytestorm.launcher.layout.CloudLayout;
import dev.bytestorm.node.template.ForwardingCheck;
import dev.bytestorm.node.template.TemplatePreparer;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoctorTest {

    @TempDir
    Path root;

    @Test
    void reportsDuplicateAddressesAndForwardingMismatch() throws IOException {
        CloudLayout layout = new CloudLayout(root);
        layout.create();
        Path backend = layout.staticServices().resolve("Lobby-1");
        Files.createDirectories(backend.resolve("config"));
        Files.writeString(backend.resolve(ForwardingCheck.PAPER_CONFIG), """
                proxies:
                  velocity:
                    enabled: true
                    online-mode: true
                    secret: 'anderes'
                """);

        Doctor doctor = new Doctor(layout,
                () -> CloudConfig.defaults().withForwardingSecret("richtig"),
                () -> List.of(),
                () -> List.of(service("Proxy-1", ServiceType.PROXY, 25565),
                        service("Lobby-1", ServiceType.SERVER, 30001),
                        service("Lobby-2", ServiceType.SERVER, 30001)),
                () -> "antwortet in 1 ms", () -> "antwortet in 2 ms");

        List<Diagnostics.Finding> findings = doctor.run();

        assertTrue(has(findings, "Adressen", "mehrfach belegt"), findings.toString());
        assertTrue(has(findings, "Forwarding",
                "Lobby-1: Secret in config/paper-global.yml weicht"), findings.toString());
        assertTrue(has(findings, "MongoDB", "antwortet in 1 ms"), findings.toString());
        assertTrue(has(findings, "Redis", "antwortet in 2 ms"), findings.toString());
        assertTrue(has(findings, "Ports", "Proxy 25565"), findings.toString());
    }

    @Test
    void reportsAServerGroupOnAProxyPlatform() {
        CloudLayout layout = new CloudLayout(root);
        ServiceGroup proxyAsServer = ServiceGroup.newBuilder()
                .setName("Proxy")
                .setType(ServiceType.SERVER)
                .setPlatform("velocity")
                .build();

        Doctor doctor = new Doctor(layout,
                () -> CloudConfig.defaults().withForwardingSecret("gemeinsam"),
                () -> List.of(proxyAsServer),
                () -> List.of(service("Proxy-1", "Proxy", ServiceType.SERVER, 30000)),
                () -> "ok", () -> "ok");

        List<Diagnostics.Finding> findings = doctor.run();

        assertTrue(has(findings, "Forwarding", "Gruppe 'Proxy' ist als SERVER angelegt"),
                findings.toString());
        assertFalse(has(findings, "Forwarding", "paper-global.yml"),
                "eine Velocity-Gruppe ist kein Paper-Backend: " + findings);
        assertFalse(has(findings, "Forwarding", "kein Proxy gefunden"),
                "die Gruppe ist als Proxy gemeint: " + findings);
    }

    @Test
    void unreachableDatabaseIsReportedInsteadOfFailing() {
        CloudLayout layout = new CloudLayout(root);

        Doctor doctor = new Doctor(layout, CloudConfig::defaults, () -> List.of(), () -> List.of(),
                () -> {
                    throw new IllegalStateException("keine Verbindung");
                },
                () -> "antwortet in 2 ms");

        List<Diagnostics.Finding> findings = doctor.run();

        assertTrue(has(findings, "MongoDB", "keine Verbindung"), findings.toString());
    }

    @Test
    void reportsStaleServerEntriesInTheProxy() throws IOException {
        CloudLayout layout = new CloudLayout(root);
        layout.create();
        Path proxy = layout.staticServices().resolve("Proxy-1");
        Files.createDirectories(proxy);
        Files.writeString(proxy.resolve(ForwardingCheck.VELOCITY_CONFIG), """
                player-info-forwarding-mode = "modern"
                forwarding-secret-file = "forwarding.secret"

                [servers]
                lobby = "127.0.0.1:30001"
                Lobby-1 = "127.0.0.1:30001"
                """);
        Files.writeString(proxy.resolve("forwarding.secret"), "gemeinsam\n");

        Doctor doctor = new Doctor(layout,
                () -> CloudConfig.defaults().withForwardingSecret("gemeinsam"),
                () -> List.of(),
                () -> List.of(service("Proxy-1", ServiceType.PROXY, 25565),
                        service("Lobby-1", ServiceType.SERVER, 30001)),
                () -> "ok", () -> "ok");

        List<Diagnostics.Finding> findings = doctor.run();

        assertTrue(has(findings, "Adressen", "veraltete Server-Einträge"), findings.toString());
        assertTrue(has(findings, "Adressen", "'lobby' zeigt auf 127.0.0.1:30001"), findings.toString());
    }

    @Test
    void aProxyWithoutTemplatesIsPointedAtTheRestartCommand() throws IOException {
        CloudLayout layout = new CloudLayout(root);
        layout.create();
        Path proxy = layout.staticServices().resolve("Proxy-1");
        Files.createDirectories(proxy);
        Files.writeString(proxy.resolve(ForwardingCheck.VELOCITY_CONFIG), """
                player-info-forwarding-mode = "NONE"
                forwarding-secret-file = "forwarding.secret"
                """);
        Files.writeString(proxy.resolve("forwarding.secret"), "fremd\n");

        List<Diagnostics.Finding> findings = new Doctor(layout,
                () -> CloudConfig.defaults().withForwardingSecret("richtig"),
                () -> List.of(),
                () -> List.of(service("Proxy-1", ServiceType.PROXY, 25565)),
                () -> "ok", () -> "ok").run();

        assertTrue(has(findings, "Forwarding", "player-info-forwarding-mode ist 'NONE'"),
                findings.toString());
        assertTrue(has(findings, "Forwarding", "'service restart Proxy-1'"),
                "ein Ordner ohne Template-Marker wird beim Neustart vorbereitet: " + findings);
    }

    @Test
    void aPreparedProxyIsPointedAtReapply() throws IOException {
        CloudLayout layout = new CloudLayout(root);
        layout.create();
        Path proxy = layout.staticServices().resolve("Proxy-1");
        Files.createDirectories(proxy);
        Files.writeString(proxy.resolve(TemplatePreparer.PREPARED_MARKER), "");
        Files.writeString(proxy.resolve(ForwardingCheck.VELOCITY_CONFIG), """
                player-info-forwarding-mode = "NONE"
                forwarding-secret-file = "forwarding.secret"
                """);
        Files.writeString(proxy.resolve("forwarding.secret"), "fremd\n");

        List<Diagnostics.Finding> findings = new Doctor(layout,
                () -> CloudConfig.defaults().withForwardingSecret("richtig"),
                () -> List.of(),
                () -> List.of(service("Proxy-1", ServiceType.PROXY, 25565)),
                () -> "ok", () -> "ok").run();

        assertTrue(has(findings, "Forwarding", "'service reapply Proxy-1'"),
                "ein schon vorbereiteter Ordner braucht reapply statt Neustart: " + findings);
    }

    @Test
    void aMissingProxyGroupIsAnError() {
        CloudLayout layout = new CloudLayout(root);
        Doctor doctor = new Doctor(layout, CloudConfig::defaults,
                () -> List.of(serverGroup("Lobby", true)),
                () -> List.of(), () -> "ok", () -> "ok");

        List<Diagnostics.Finding> findings = doctor.run();

        assertTrue(has(findings, "Gruppen", "keine Proxy-Gruppe"), findings.toString());
        assertTrue(has(findings, "Gruppen", "--type PROXY"), findings.toString());
    }

    @Test
    void aBackendWithoutFallbackIsReportedWithItsGroupName() {
        CloudLayout layout = new CloudLayout(root);
        Doctor doctor = new Doctor(layout, CloudConfig::defaults,
                () -> List.of(proxyGroup(), serverGroup("Lobby", false)),
                () -> List.of(), () -> "ok", () -> "ok");

        List<Diagnostics.Finding> findings = doctor.run();

        assertTrue(has(findings, "Gruppen", "kein Fallback-Ziel"), findings.toString());
        assertTrue(has(findings, "Gruppen", "group edit Lobby --fallback"), findings.toString());
        assertFalse(has(findings, "Gruppen", "keine Proxy-Gruppe"), findings.toString());
    }

    @Test
    void aServerGroupWithoutAPeerIsReported() {
        CloudLayout layout = new CloudLayout(root);
        Doctor doctor = new Doctor(layout, CloudConfig::defaults,
                () -> List.of(proxyGroup()),
                () -> List.of(), () -> "ok", () -> "ok");

        List<Diagnostics.Finding> findings = doctor.run();

        assertTrue(has(findings, "Gruppen", "keine Server-Gruppe"), findings.toString());
    }

    @Test
    void anEmptyGroupListIsReported() {
        CloudLayout layout = new CloudLayout(root);
        Doctor doctor = new Doctor(layout, CloudConfig::defaults, () -> List.of(), () -> List.of(),
                () -> "ok", () -> "ok");

        List<Diagnostics.Finding> findings = doctor.run();

        assertTrue(has(findings, "Gruppen", "keine Gruppen definiert"), findings.toString());
    }

    @Test
    void aProxyAndAFallbackTargetAreReportedAsConfigured() {
        CloudLayout layout = new CloudLayout(root);
        Doctor doctor = new Doctor(layout, CloudConfig::defaults,
                () -> List.of(proxyGroup(), serverGroup("Lobby", true)),
                () -> List.of(), () -> "ok", () -> "ok");

        List<Diagnostics.Finding> findings = doctor.run();

        assertTrue(has(findings, "Gruppen", "Proxy 'Proxy', Fallback 'Lobby'"), findings.toString());
    }

    private static ServiceGroup proxyGroup() {
        return ServiceGroup.newBuilder()
                .setName("Proxy")
                .setType(ServiceType.PROXY)
                .setPlatform("velocity")
                .build();
    }

    private static ServiceGroup serverGroup(String name, boolean fallback) {
        return ServiceGroup.newBuilder()
                .setName(name)
                .setType(ServiceType.SERVER)
                .setPlatform("paper")
                .setFallback(fallback)
                .build();
    }

    private static ServiceInfo service(String name, ServiceType type, int port) {
        return service(name, type == ServiceType.PROXY ? "Proxy" : "Lobby", type, port);
    }

    private static ServiceInfo service(String name, String group, ServiceType type, int port) {
        return ServiceInfo.newBuilder()
                .setName(name)
                .setGroup(group)
                .setHost("127.0.0.1")
                .setPort(port)
                .setState(ServiceState.ONLINE)
                .setType(type)
                .build();
    }

    private static boolean has(List<Diagnostics.Finding> findings, String area, String text) {
        return findings.stream().anyMatch(finding -> finding.area().equals(area)
                && finding.detail().contains(text));
    }
}
