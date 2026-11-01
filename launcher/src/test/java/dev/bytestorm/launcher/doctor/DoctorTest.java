package dev.bytestorm.launcher.doctor;

import dev.bytestorm.api.module.AccessGrant;
import dev.bytestorm.cli.Diagnostics;
import dev.bytestorm.core.config.CloudConfig;
import dev.bytestorm.core.config.MongoSettings;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.core.config.SecuritySettings;
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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(has(findings, "Ports", "Proxy ab 25565"), findings.toString());
    }

    @Test
    void aProxyOnTheNextPortOfThePoolIsAccepted() {
        CloudLayout layout = new CloudLayout(root);
        Doctor doctor = new Doctor(layout, CloudConfig::defaults, () -> List.of(),
                () -> List.of(service("Proxy-1", ServiceType.PROXY, 25566)),
                () -> "ok", () -> "ok");

        List<Diagnostics.Finding> findings = doctor.run();

        assertTrue(has(findings, "Ports", "registrierte Ports passen"), findings.toString());
        assertFalse(has(findings, "Ports", "Proxy-Pool"), findings.toString());
    }

    @Test
    void aProxyOutsideThePoolIsReportedWithThePoolRange() {
        CloudLayout layout = new CloudLayout(root);
        Doctor doctor = new Doctor(layout, CloudConfig::defaults, () -> List.of(),
                () -> List.of(service("Proxy-1", ServiceType.PROXY, 30001)),
                () -> "ok", () -> "ok");

        List<Diagnostics.Finding> findings = doctor.run();

        assertTrue(has(findings, "Ports", "der Proxy-Pool ist 25565–29999"), findings.toString());
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
    void fixReappliesTemplatesToAStoppedProxyAndChecksAgain() throws IOException {
        CloudLayout layout = new CloudLayout(root);
        layout.create();
        Path proxy = layout.staticServices().resolve("Proxy-1");
        Files.createDirectories(proxy);
        Files.writeString(proxy.resolve(ForwardingCheck.VELOCITY_CONFIG), """
                player-info-forwarding-mode = "NONE"
                forwarding-secret-file = "forwarding.secret"
                """);
        Files.writeString(proxy.resolve("forwarding.secret"), "fremd\n");
        List<String> repaired = new ArrayList<>();

        Doctor doctor = new Doctor(layout,
                () -> CloudConfig.defaults().withForwardingSecret("richtig"),
                () -> List.of(),
                () -> List.of(service("Proxy-1", ServiceType.PROXY, ServiceState.STOPPED, 25565)),
                () -> "ok", () -> "ok",
                service -> {
                    repaired.add(service);
                    try {
                        Files.writeString(proxy.resolve(ForwardingCheck.VELOCITY_CONFIG), """
                                player-info-forwarding-mode = "modern"
                                forwarding-secret-file = "forwarding.secret"
                                """);
                        Files.writeString(proxy.resolve("forwarding.secret"), "richtig\n");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

        List<Diagnostics.Finding> findings = doctor.fix();

        assertEquals(List.of("Proxy-1"), repaired);
        assertTrue(has(findings, "Reparatur", "Proxy-1: Templates erneut angewendet"),
                findings.toString());
        assertFalse(has(findings, "Forwarding", "player-info-forwarding-mode"),
                "nach dem erneuten Anwenden passt die Datei: " + findings);
    }

    @Test
    void fixRestartsARunningProxyWithBrokenForwarding() throws IOException {
        CloudLayout layout = new CloudLayout(root);
        layout.create();
        Path proxy = layout.staticServices().resolve("Proxy-1");
        Files.createDirectories(proxy);
        Files.writeString(proxy.resolve(ForwardingCheck.VELOCITY_CONFIG), """
                player-info-forwarding-mode = "NONE"
                forwarding-secret-file = "forwarding.secret"
                """);
        Files.writeString(proxy.resolve("forwarding.secret"), "fremd\n");
        List<String> actions = new ArrayList<>();

        Doctor doctor = new Doctor(layout,
                () -> CloudConfig.defaults().withForwardingSecret("richtig"),
                () -> List.of(),
                () -> List.of(service("Proxy-1", ServiceType.PROXY, 25565)),
                () -> "ok", () -> "ok",
                new Doctor.Repair() {
                    @Override
                    public void reapply(String service) {
                        actions.add("reapply:" + service);
                    }

                    @Override
                    public void restart(String service) {
                        actions.add("restart:" + service);
                    }
                });

        List<Diagnostics.Finding> findings = doctor.fix();

        assertEquals(List.of("restart:Proxy-1"), actions,
                "ein laufender Proxy muss stoppen, damit die Templates greifen");
        assertTrue(has(findings, "Reparatur", "Proxy-1 neu gestartet (ONLINE)"), findings.toString());
    }

    @Test
    void fixReappliesTemplatesToAStoppedBackend() throws IOException {
        CloudLayout layout = new CloudLayout(root);
        layout.create();
        Path backend = layout.staticServices().resolve("Lobby-1");
        Files.createDirectories(backend.resolve("config"));
        Files.writeString(backend.resolve(ForwardingCheck.PAPER_CONFIG), """
                proxies:
                  velocity:
                    enabled: true
                    online-mode: true
                    secret: 'falsch'
                """);
        List<String> repaired = new ArrayList<>();

        Doctor doctor = new Doctor(layout,
                () -> CloudConfig.defaults().withForwardingSecret("richtig"),
                () -> List.of(),
                () -> List.of(service("Lobby-1", ServiceType.SERVER, ServiceState.STOPPED, 30001)),
                () -> "ok", () -> "ok",
                service -> {
                    repaired.add(service);
                    try {
                        Files.writeString(backend.resolve(ForwardingCheck.PAPER_CONFIG), """
                                proxies:
                                  velocity:
                                    enabled: true
                                    online-mode: true
                                    secret: 'richtig'
                                """);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

        List<Diagnostics.Finding> findings = doctor.fix();

        assertEquals(List.of("Lobby-1"), repaired);
        assertTrue(has(findings, "Reparatur", "Lobby-1: Templates erneut angewendet"),
                findings.toString());
        assertFalse(has(findings, "Forwarding", "paper-global.yml weicht"),
                "nach dem erneuten Anwenden passt das Backend: " + findings);
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

    @Test
    void anInsecureLocalDatastoreIsAWarning() {
        CloudLayout layout = new CloudLayout(root);
        List<Diagnostics.Finding> findings = new Doctor(layout,
                () -> config(new MongoSettings("mongodb://user:pass@127.0.0.1:27017", "db"),
                        new RedisSettings("redis://localhost:6379", null),
                        new SecuritySettings(false, null, true)),
                () -> List.of(), () -> List.of(), () -> "ok", () -> "ok").run();

        assertTrue(has(findings, "Sicherheit",
                "Redis ohne Anmeldung (security.allowInsecureRedis)"), findings.toString());
        assertTrue(has(findings, "Sicherheit",
                "MongoDB nur lokal (127.0.0.1) und mit Anmeldung"), findings.toString());
        assertTrue(findings.stream().filter(finding -> finding.area().equals("Sicherheit"))
                .noneMatch(finding -> finding.severity() == Diagnostics.Severity.ERROR),
                findings.toString());
    }

    @Test
    void anInsecureDatastoreOutsideLocalhostIsAnError() {
        CloudLayout layout = new CloudLayout(root);
        List<Diagnostics.Finding> findings = new Doctor(layout,
                () -> config(new MongoSettings("mongodb://localhost:27017", "db"),
                        new RedisSettings("redis://10.0.0.5:6379", null),
                        new SecuritySettings(true, null, null)),
                () -> List.of(), () -> List.of(), () -> "ok", () -> "ok").run();

        assertTrue(has(findings, "Sicherheit",
                "Redis ohne Anmeldung (security.allowInsecureRedis) auf 10.0.0.5"),
                findings.toString());
        assertTrue(has(findings, "Sicherheit",
                "MongoDB ohne Anmeldung (security.allowInsecureMongo)"), findings.toString());
        assertTrue(findings.stream().anyMatch(finding -> finding.area().equals("Sicherheit")
                && finding.severity() == Diagnostics.Severity.ERROR), findings.toString());
    }

    @Test
    void aPublicDatastoreAddressIsAWarning() {
        CloudLayout layout = new CloudLayout(root);
        List<Diagnostics.Finding> findings = new Doctor(layout,
                () -> config(new MongoSettings("mongodb://user:pass@db.example.com:27017", "db"),
                        new RedisSettings("redis://10.0.0.5:6379", "geheim"),
                        SecuritySettings.defaults()),
                () -> List.of(), () -> List.of(), () -> "ok", () -> "ok").run();

        assertTrue(has(findings, "Sicherheit",
                "MongoDB auf db.example.com — nicht als lokal erkennbar"), findings.toString());
        assertTrue(has(findings, "Sicherheit",
                "Redis im privaten Netz (10.0.0.5) und mit Anmeldung"), findings.toString());
        assertTrue(findings.stream().filter(finding -> finding.area().equals("Sicherheit"))
                .noneMatch(finding -> finding.severity() == Diagnostics.Severity.ERROR),
                findings.toString());
    }

    @Test
    void aDatastoreWithoutCredentialsIsReportedEvenWithoutTheException() {
        CloudLayout layout = new CloudLayout(root);
        List<Diagnostics.Finding> findings = new Doctor(layout, CloudConfig::defaults,
                () -> List.of(), () -> List.of(), () -> "ok", () -> "ok").run();

        assertTrue(has(findings, "Sicherheit", "MongoDB ohne Anmeldung"), findings.toString());
        assertTrue(has(findings, "Sicherheit", "Redis ohne Anmeldung"), findings.toString());
    }

    @Test
    void aRestApiOnAllInterfacesWithAFullKeyIsAWarning() {
        CloudLayout layout = new CloudLayout(root);
        List<Diagnostics.Finding> findings = new Doctor(layout, CloudConfig::defaults,
                () -> List.of(), () -> List.of(), () -> "ok", () -> "ok", null,
                () -> new RestExposure(true, "0.0.0.0", 8080,
                        List.of(new AccessGrant("apiKey", true, List.of("*"))))).run();

        assertTrue(has(findings, "REST", "0.0.0.0:8080 (alle Adressen)"), findings.toString());
        assertTrue(has(findings, "REST", "Key 'apiKey' hat vollen Zugriff (*)"), findings.toString());
        assertTrue(findings.stream().anyMatch(finding -> finding.area().equals("REST")
                && finding.severity() == Diagnostics.Severity.WARNING), findings.toString());
    }

    @Test
    void aRestApiOnLoopbackWithAFullKeyIsFine() {
        CloudLayout layout = new CloudLayout(root);
        List<Diagnostics.Finding> findings = new Doctor(layout, CloudConfig::defaults,
                () -> List.of(), () -> List.of(), () -> "ok", () -> "ok", null,
                () -> new RestExposure(true, "localhost", 8080,
                        List.of(new AccessGrant("apiKey", true, List.of("*"))))).run();

        assertTrue(has(findings, "REST", "nur lokal (localhost:8080)"), findings.toString());
        assertFalse(findings.stream().anyMatch(finding -> finding.area().equals("REST")
                && finding.severity() == Diagnostics.Severity.WARNING), findings.toString());
    }

    @Test
    void aRestApiWithOnlyScopedKeysMayListenOnAllInterfaces() {
        CloudLayout layout = new CloudLayout(root);
        List<Diagnostics.Finding> findings = new Doctor(layout, CloudConfig::defaults,
                () -> List.of(), () -> List.of(), () -> "ok", () -> "ok", null,
                () -> new RestExposure(true, "0.0.0.0", 8080,
                        List.of(new AccessGrant("monitoring", false,
                                List.of("monitoring.read", "services.read"))))).run();

        assertTrue(has(findings, "REST", "0.0.0.0:8080 — kein Key mit vollem Zugriff"),
                findings.toString());
        assertFalse(findings.stream().anyMatch(finding -> finding.area().equals("REST")
                && finding.severity() == Diagnostics.Severity.WARNING), findings.toString());
    }

    @Test
    void anInactiveRestModuleIsNotReportedAsExposed() {
        CloudLayout layout = new CloudLayout(root);
        List<Diagnostics.Finding> findings = new Doctor(layout, CloudConfig::defaults,
                () -> List.of(), () -> List.of(), () -> "ok", () -> "ok", null,
                () -> new RestExposure(false, "0.0.0.0", 8080,
                        List.of(new AccessGrant("apiKey", true, List.of("*"))))).run();

        assertTrue(has(findings, "REST", "Modul nicht aktiv"), findings.toString());
        assertFalse(findings.stream().anyMatch(finding -> finding.area().equals("REST")
                && finding.severity() == Diagnostics.Severity.WARNING), findings.toString());
    }

    private static CloudConfig config(MongoSettings mongo, RedisSettings redis,
                                      SecuritySettings security) {
        return new CloudConfig(mongo, redis, null, null, null, null, null, null, null, security);
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

    private static ServiceInfo service(String name, ServiceType type, ServiceState state, int port) {
        return service(name, type, port).toBuilder().setState(state).build();
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
