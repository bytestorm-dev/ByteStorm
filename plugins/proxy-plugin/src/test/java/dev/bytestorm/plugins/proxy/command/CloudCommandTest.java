package dev.bytestorm.plugins.proxy.command;

import dev.bytestorm.plugins.proxy.cloud.CloudClient;
import dev.bytestorm.plugins.proxy.cloud.CloudClient.CrashView;
import dev.bytestorm.plugins.proxy.cloud.CloudException;
import dev.bytestorm.plugins.proxy.message.Messages;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudCommandTest {

    private static final String LOBBY_ID = "1e1f0dd4-0000-4000-8000-000000000001";

    private static final String PROXY_ID = "1e1f0dd4-0000-4000-8000-000000000002";

    private static final String PLAYER_ID = "1e1f0dd4-0000-4000-8000-000000000003";

    private static final long CRASH_TIME = 1_700_000_000_000L;

    private final FakeCloud cloud = new FakeCloud();

    private final FakeControl control = new FakeControl();

    @Test
    void helpNamesTheCommandsAndTheirAliases() throws Exception {
        String help = joined(run("help"));

        assertTrue(help.contains("status"), help);
        assertTrue(help.contains("service"), help);
        assertTrue(help.contains("maintenance"), help);
        assertTrue(help.contains("reapply"), help);
        assertTrue(help.contains("online"), help);
        assertTrue(help.contains("restart-crashed"), help);
        assertTrue(help.contains("bytestorm.command"), "die Nodes stehen in der Hilfe: " + help);
        assertTrue(help.contains("tablist"), help);
        assertTrue(help.contains("language"), help);
        assertTrue(help.contains("/c"), "Aliasse stehen in der Hilfe: " + help);
        assertTrue(help.contains("/bytestorm"), help);
    }

    @Test
    void anEmptyLineShowsTheHelpToo() throws Exception {
        assertTrue(joined(run("")).contains("status"));
        assertTrue(cloud.calls.isEmpty(), "die Hilfe fragt die Cloud nicht");
    }

    @Test
    void withoutPermissionNothingHappens() throws Exception {
        List<Reply> replies = new CloudCommand(cloud, true, control)
                .execute("service kill Lobby-1", allow(), Messages.load("en", null));

        assertEquals(1, replies.size());
        assertTrue(first(replies).contains("not allowed"), first(replies));
        assertTrue(cloud.calls.isEmpty(), "kein Aufruf ohne Permission: " + cloud.calls);
    }

    @Test
    void everyCommandChecksItsOwnNode() throws Exception {
        Messages messages = Messages.load("en", null);
        CloudCommand command = new CloudCommand(cloud, true, control);

        assertTrue(plain(first(command.execute("status", allow("bytestorm.command.status"), messages)))
                .contains("1/2"), "die eigene Node reicht für status");

        String denied = first(command.execute("service kill Lobby-1",
                allow("bytestorm.command.status"), messages));
        assertTrue(denied.contains("bytestorm.command.service.kill"),
                "die Ablehnung nennt die fehlende Node: " + denied);
        assertTrue(cloud.calls.isEmpty(), "ohne Node kein Aufruf: " + cloud.calls);

        assertTrue(first(command.execute("tablist off", allow(), messages))
                .contains("bytestorm.command.tablist"), "auch die Tablist hat eine Node");
        assertTrue(control.tablist, "ohne Node bleibt der Schalter stehen");
    }

    @Test
    void wildcardsGrantFamiliesAndTheLeafAcrossGroups() throws Exception {
        Messages messages = Messages.load("en", null);
        CloudCommand command = new CloudCommand(cloud, true, control);

        assertTrue(first(command.execute("service restart Lobby-1",
                allow("bytestorm.command.service.*"), messages)).contains("Restarted"),
                "die Gruppen-Wildcard deckt die Unterbefehle ab");

        String groupDenied = first(command.execute("group list",
                allow("bytestorm.command.service.*"), messages));
        assertTrue(groupDenied.contains("bytestorm.command.group.list"),
                "Gruppen sind getrennt: " + groupDenied);

        assertTrue(first(command.execute("player kick Notch",
                allow("bytestorm.command.kick.*"), messages)).contains("Disconnected"),
                "bytestorm.command.kick.* deckt den Kick ab");
        assertTrue(joined(command.execute("group list",
                allow("bytestorm.command.*"), messages)).contains("Lobby"),
                "die globale Wildcard erlaubt alles");

        assertEquals(List.of("restart:" + LOBBY_ID,
                "kick:" + PLAYER_ID + ":Removed by an administrator"), cloud.calls);
    }

    @Test
    void playerConnectAndKickHaveTheirOwnNodes() throws Exception {
        Messages messages = Messages.load("en", null);
        CloudCommand command = new CloudCommand(cloud, true, control);
        CloudPermissions connectOnly = allow("bytestorm.command.player.connect");

        assertTrue(first(command.execute("player connect Notch Lobby-1", connectOnly, messages))
                .contains("Lobby-1"), "connect reicht für sich selbst");
        assertTrue(first(command.execute("player kick Notch", connectOnly, messages))
                .contains("bytestorm.command.player.kick"), "kick ist eine eigene Node");
        assertTrue(first(command.execute("player list", connectOnly, messages))
                .contains("bytestorm.command.player.list"), "die Liste bleibt getrennt");
        assertEquals(List.of("connect:" + PLAYER_ID + ":Lobby-1"), cloud.calls,
                "ohne Node kein Kick");
    }

    @Test
    void aDisabledCloudExplainsWhatToConfigure() throws Exception {
        List<Reply> replies = new CloudCommand(cloud, false, control)
                .execute("status", CloudPermissions.allowAll(), Messages.load("en", null));

        assertTrue(first(replies).contains("cloud.enabled"), first(replies));
        assertTrue(cloud.calls.isEmpty());
    }

    @Test
    void statusCountsOnlineServicesAndListsThem() throws Exception {
        List<Reply> replies = run("status");

        assertTrue(plain(first(replies)).contains("1/2"),
                "ein Service von zwei ist online: " + first(replies));
        assertEquals(3, replies.size());
        assertTrue(text(replies, 1).contains("Lobby-1"), text(replies, 1));
        assertTrue(text(replies, 1).contains("3/100"), "Spielerzahl je Service: " + text(replies, 1));
        assertTrue(text(replies, 2).contains("Proxy-1"), text(replies, 2));
    }

    @Test
    void statusShowsTheCrashBudgetAndTheLastCrash() throws Exception {
        List<Reply> running = run("status");
        assertTrue(text(running, 1).contains("2/3"), "Budget je Service: " + text(running, 1));

        cloud.lobbyCrash = new CrashView(0, 3, "OutOfMemoryError", CRASH_TIME, 1);
        List<Reply> replies = run("status");

        assertEquals(6, replies.size(),
                "Kopf, zwei Services, Absturz-Kopf, Absturz und Neustart-Hinweis: " + joined(replies));
        assertTrue(text(replies, 1).contains("0/3"), "erschöpftes Budget: " + text(replies, 1));
        assertTrue(text(replies, 3).contains("Last crashes"), text(replies, 3));
        assertTrue(text(replies, 4).contains("Lobby-1"), text(replies, 4));
        assertTrue(text(replies, 4).contains("OutOfMemoryError"), text(replies, 4));
        assertTrue(plain(text(replies, 4)).contains("exit 1"), text(replies, 4));
    }

    @Test
    void aCrashedServiceShowsItsGroupAndOneClickForAll() throws Exception {
        cloud.lobbyCrash = new CrashView(0, 3, "OutOfMemoryError", CRASH_TIME, 1);

        List<Reply> replies = run("status");
        Reply row = replies.get(4);
        Reply hint = replies.get(5);

        assertTrue(row.text().contains("Lobby"), "die Gruppe steht in der Absturzzeile: " + row.text());
        assertTrue(hint.clickable(), "der Neustart-Hinweis ist klickbar");
        assertEquals("/cloud service restart-crashed", hint.command());
        assertTrue(plain(hint.text()).contains("(1)"), hint.text());
        assertTrue(replies.subList(0, 5).stream().noneMatch(Reply::clickable),
                "nur der eine Hinweis führt einen Befehl aus");
    }

    @Test
    void severalCrashesShareASingleRestartHint() throws Exception {
        cloud.lobbyCrash = new CrashView(0, 3, "OutOfMemoryError", CRASH_TIME, 1);
        cloud.proxyCrash = new CrashView(1, 3, "Start-Timeout", CRASH_TIME, 2);

        List<Reply> replies = run("status");

        assertEquals(7, replies.size(), joined(replies));
        assertEquals(1, replies.stream().filter(Reply::clickable).count(),
                "ein Klick startet alle: " + joined(replies));
        assertEquals("/cloud service restart-crashed", replies.get(6).command());
        assertTrue(plain(replies.get(6).text()).contains("(2)"), replies.get(6).text());

        List<Reply> restarted = run("service restart-crashed");
        assertTrue(first(restarted).contains("Lobby-1"), first(restarted));
        assertTrue(first(restarted).contains("Proxy-1"), first(restarted));
        assertEquals(List.of("restart:" + LOBBY_ID, "restart:" + PROXY_ID), cloud.calls);
    }

    @Test
    void restartingCrashedServicesReportsAnEmptyListAndFailures() throws Exception {
        assertTrue(first(run("service restart-crashed")).contains("No crashed services"),
                "ohne Absturz gibt es nichts zu starten");
        assertTrue(cloud.calls.isEmpty());

        cloud.lobbyCrash = new CrashView(0, 3, "OutOfMemoryError", CRASH_TIME, 1);
        cloud.restartFailure = new CloudException("Budget ersch\u00f6pft");

        String line = first(run("service restart-crashed"));
        assertTrue(line.contains("Budget ersch\u00f6pft"), line);
        assertTrue(line.contains("Lobby-1"), "der Service bleibt benannt: " + line);
    }

    @Test
    void serviceInfoNamesTheLastCrash() throws Exception {
        cloud.lobbyCrash = new CrashView(1, 3, "Start-Timeout", CRASH_TIME, -1);

        String info = joined(run("service info Lobby-1"));

        assertTrue(info.contains("Start-Timeout"), info);
        assertTrue(plain(info).contains("exit -1"), info);
    }

    @Test
    void serviceCommandsResolveTheNameRegardlessOfCase() throws Exception {
        assertTrue(first(run("service stop lobby-1")).contains("Lobby-1"));
        assertTrue(first(run("service stop Lobby-1 force")).contains("Stopped"));
        assertTrue(first(run("service kill Lobby-1")).contains("Killed"));
        assertTrue(first(run("service restart Lobby-1")).contains("Restarted"));

        assertEquals(List.of("stop:" + LOBBY_ID + ":false",
                "stop:" + LOBBY_ID + ":true",
                "stop:" + LOBBY_ID + ":true",
                "restart:" + LOBBY_ID), cloud.calls);
    }

    @Test
    void reapplyAndOnlineReachTheCloud() throws Exception {
        assertTrue(first(run("service reapply Lobby-1")).contains("Templates"));
        assertTrue(first(run("service online lobby-1")).contains("ONLINE"));

        assertEquals(List.of("reapply:" + LOBBY_ID, "online:" + LOBBY_ID), cloud.calls);
    }

    @Test
    void execSendsTheWholeRemainderOfTheLine() throws Exception {
        List<Reply> replies = run("service exec Proxy-1  say hallo   welt ");

        assertTrue(first(replies).contains("say hallo welt"), first(replies));
        assertEquals(List.of("command:" + PROXY_ID + ":say hallo welt"), cloud.calls);
    }

    @Test
    void startAsksForTheGroupAndNamesTheNewService() throws Exception {
        List<Reply> replies = run("service start Lobby");

        assertTrue(first(replies).contains("Lobby-2"), first(replies));
        assertTrue(first(replies).contains("STARTING"), first(replies));
        assertEquals(List.of("start:Lobby"), cloud.calls);
    }

    @Test
    void serviceListShowsStatePlayersAndPort() throws Exception {
        List<Reply> replies = run("service list");

        assertTrue(first(replies).contains("2"), first(replies));
        assertTrue(text(replies, 1).contains("ONLINE"), text(replies, 1));
        assertTrue(text(replies, 1).contains("30001"), text(replies, 1));
    }

    @Test
    void logsShowTheTailWithTheDefaultCount() throws Exception {
        List<Reply> replies = run("service logs Lobby-1");

        assertTrue(first(replies).contains("Lobby-1"), first(replies));
        assertEquals("[INFO] Server gestartet", text(replies, 1));
        assertEquals("[WARN] Wenig Speicher", text(replies, 2));
        assertEquals(List.of("logs:" + LOBBY_ID + ":20"), cloud.calls);
    }

    @Test
    void logsCapTheRequestedCount() throws Exception {
        run("service logs Lobby-1 200");

        assertEquals(List.of("logs:" + LOBBY_ID + ":50"), cloud.calls,
                "mehr als 50 Zeilen bleiben im Chat unlesbar");
    }

    @Test
    void anEmptyLogBufferIsReported() throws Exception {
        cloud.logLines = List.of();

        List<Reply> replies = run("service logs Lobby-1");

        assertEquals(1, replies.size(), joined(replies));
        assertTrue(first(replies).contains("No log lines"), first(replies));
    }

    @Test
    void unknownNamesAndCommandsAreNamed() throws Exception {
        assertTrue(first(run("service info Nope-9")).contains("Nope-9"));
        assertTrue(first(run("groupinfo")).contains("groupinfo"));
        assertTrue(first(run("service fliegen Lobby-1")).contains("service fliegen"));
        assertTrue(cloud.calls.isEmpty(), "Unbekanntes erreicht die Cloud nicht: " + cloud.calls);
    }

    @Test
    void serviceInfoShowsAllFields() throws Exception {
        String info = joined(run("service info Lobby-1"));

        assertTrue(info.contains(LOBBY_ID), info);
        assertTrue(info.contains("Lobby"), info);
        assertTrue(info.contains("ONLINE"), info);
        assertTrue(info.contains("30001"), info);
        assertTrue(info.contains("PAPER"), info);
    }

    @Test
    void groupsCanBeListedAndScaled() throws Exception {
        String list = joined(run("group list"));
        assertTrue(list.contains("Lobby"), list);
        assertTrue(list.contains("on"), "Proxy steht in Wartung: " + list);

        assertTrue(first(run("group scale Lobby 3")).contains("3"));
        assertEquals(List.of("scale:Lobby:3"), cloud.calls);
    }

    @Test
    void scalingNeedsANumber() throws Exception {
        assertTrue(first(run("group scale Lobby viele")).contains("Usage"));
        assertTrue(first(run("group scale Lobby -1")).contains("Usage"));
        assertTrue(cloud.calls.isEmpty(), "kein Aufruf ohne Zahl: " + cloud.calls);
    }

    @Test
    void maintenanceTakesOnAndOff() throws Exception {
        List<Reply> replies = run("maintenance lobby on");

        assertTrue(first(replies).contains("Lobby"), first(replies));
        assertTrue(first(replies).contains("on"), first(replies));

        run("maintenance Lobby off");
        assertEquals(List.of("maintenance:Lobby:true", "maintenance:Lobby:false"), cloud.calls);
        assertTrue(first(run("maintenance Lobby vielleicht")).contains("maintenance"));
    }

    @Test
    void playersAreResolvedByNameAndDisconnected() throws Exception {
        assertTrue(text(run("player list"), 1).contains("Notch"));
        assertTrue(first(run("player connect notch Lobby-1")).contains("Lobby-1"));
        assertTrue(first(run("player kick NOTCH")).contains("Notch"));
        assertTrue(first(run("player kick Notch Bitte lesen")).contains("Notch"));

        assertEquals(List.of("connect:" + PLAYER_ID + ":Lobby-1",
                "kick:" + PLAYER_ID + ":Removed by an administrator",
                "kick:" + PLAYER_ID + ":Bitte lesen"), cloud.calls);
    }

    @Test
    void tablistStateCanBeReadAndToggled() throws Exception {
        assertTrue(first(run("tablist")).contains("on"), "Tablist ist an");

        assertTrue(first(run("tablist off")).contains("off"));
        assertFalse(control.tablist, "der Schalter landet im Plugin");
        assertTrue(cloud.calls.isEmpty(), "die Tablist braucht keine Cloud");

        assertTrue(first(run("tablist an")).contains("on"));
        assertTrue(control.tablist);
        assertTrue(first(run("tablist vielleicht")).contains("/cloud tablist on|off"));
    }

    @Test
    void aFailingTablistWriteExplainsTheReason() throws Exception {
        control.failure = new IllegalStateException("config.json ist schreibgeschützt");

        String line = first(run("tablist off"));

        assertTrue(line.contains("schreibgeschützt"), line);
        assertTrue(control.tablist, "ohne Schreibrecht bleibt der alte Zustand");
    }

    @Test
    void tablistAndLanguageWorkWhileTheCloudIsOff() throws Exception {
        CloudCommand command = new CloudCommand(cloud, false, control);
        Messages messages = Messages.load("en", null);

        assertTrue(first(command.execute("tablist off", CloudPermissions.allowAll(), messages))
                .contains("off"));
        assertTrue(first(command.execute("language", CloudPermissions.allowAll(), messages))
                .contains("en"));
        assertTrue(first(command.execute("status", CloudPermissions.allowAll(), messages))
                .contains("cloud.enabled"), "Cloud-Kommandos bleiben aus");
    }

    @Test
    void languageShowsTheUsedOneAndTheFallback() throws Exception {
        assertTrue(first(run("language")).contains("en"));

        control.language = "fr";
        control.effective = "en";
        String line = first(run("language"));

        assertTrue(line.contains("en"), line);
        assertTrue(line.contains("fr"), "die eingestellte Sprache bleibt sichtbar: " + line);
    }

    @Test
    void interactiveViewsStayInTheConsole() throws Exception {
        assertTrue(first(run("watch")).contains("interactive console"));
        assertTrue(first(run("screen")).contains("interactive console"));
        assertTrue(cloud.calls.isEmpty());
    }

    @Test
    void aCloudErrorShowsTheReason() throws Exception {
        cloud.failure = new CloudException("Gruppe 'Lobby' ist im Wartungsmodus");

        String line = first(run("service start Lobby"));

        assertTrue(line.contains("Wartungsmodus"), line);
    }

    @Test
    void germanRepliesComeFromTheGermanFile() throws Exception {
        List<Reply> replies = new CloudCommand(cloud, true, control)
                .execute("status", CloudPermissions.allowAll(), Messages.load("de", null));

        assertTrue(first(replies).contains("online"), first(replies));
        assertTrue(first(replies).contains("Gruppen"), first(replies));
        assertTrue(joined(new CloudCommand(cloud, true, control)
                .execute("watch", CloudPermissions.allowAll(), Messages.load("de", null)))
                .contains("Konsole"));

        String denied = first(new CloudCommand(cloud, true, control)
                .execute("service kill Lobby-1", allow(), Messages.load("de", null)));
        assertTrue(denied.contains("bytestorm.command.service.kill"), denied);
    }

    private List<Reply> run(String line) throws Exception {
        return new CloudCommand(cloud, true, control)
                .execute(line, CloudPermissions.allowAll(), Messages.load("en", null));
    }

    private static CloudPermissions allow(String... nodes) {
        Set<String> granted = Set.of(nodes);
        return new CloudPermissions(granted::contains);
    }

    private static String first(List<Reply> replies) {
        return replies.get(0).text();
    }

    private static String text(List<Reply> replies, int index) {
        return replies.get(index).text();
    }

    private static String joined(List<Reply> replies) {
        return replies.stream().map(Reply::text).collect(Collectors.joining("\n"));
    }

    /** @return der Text ohne {@code &}-Farbcodes, damit Zahlen prüfbar bleiben */
    private static String plain(String text) {
        return text.replaceAll("&[0-9a-fk-or]", "");
    }

    /** Plugin-Zustand ohne Dateisystem. */
    private static final class FakeControl implements PluginControl {

        private boolean tablist = true;
        private String language = "en";
        private String effective = "en";
        private RuntimeException failure;

        @Override
        public boolean tablistEnabled() {
            return tablist;
        }

        @Override
        public void tablistEnabled(boolean enabled) {
            if (failure != null) {
                throw failure;
            }
            tablist = enabled;
        }

        @Override
        public String language() {
            return language;
        }

        @Override
        public String effectiveLanguage() {
            return effective;
        }
    }

    /** Cloud, die Aufrufe mitschreibt und auf Wunsch scheitert. */
    private static final class FakeCloud implements CloudClient {

        private final List<String> calls = new ArrayList<>();
        private RuntimeException failure;
        private RuntimeException restartFailure;
        private CrashView lobbyCrash = new CrashView(2, 3, "", 0, 0);
        private CrashView proxyCrash = new CrashView(1, 3, "", 0, 0);
        private List<String> logLines = List.of("[INFO] Server gestartet", "[WARN] Wenig Speicher");

        @Override
        public List<ServiceView> services() {
            check();
            return List.of(
                    new ServiceView(LOBBY_ID, "Lobby-1", "Lobby", "ONLINE", 30001, 3, 100, "PAPER", lobbyCrash),
                    new ServiceView(PROXY_ID, "Proxy-1", "Proxy", "STARTING", 0, 0, 0, "VELOCITY",
                            proxyCrash));
        }

        @Override
        public List<GroupView> groups() {
            check();
            return List.of(
                    new GroupView("Lobby", "SERVER", "STATIC", false, 1, 4, 100),
                    new GroupView("Proxy", "PROXY", "STATIC", true, 1, 1, 0));
        }

        @Override
        public List<PlayerView> players() {
            check();
            return List.of(new PlayerView(PLAYER_ID, "Notch", "Proxy-1", "Lobby-1"));
        }

        @Override
        public List<String> logs(String serviceId, int lines) {
            calls.add("logs:" + serviceId + ":" + lines);
            check();
            return logLines;
        }

        @Override
        public ServiceView start(String group) {
            calls.add("start:" + group);
            check();
            return new ServiceView(LOBBY_ID, "Lobby-2", group, "STARTING", 0, 0, 0, "PAPER",
                    CrashView.none());
        }

        @Override
        public void stop(String serviceId, boolean force) {
            calls.add("stop:" + serviceId + ":" + force);
        }

        @Override
        public void restart(String serviceId) {
            calls.add("restart:" + serviceId);
            check();
            if (restartFailure != null) {
                throw restartFailure;
            }
        }

        @Override
        public void execute(String serviceId, String command) {
            calls.add("command:" + serviceId + ":" + command);
        }

        @Override
        public void reapply(String serviceId) {
            calls.add("reapply:" + serviceId);
        }

        @Override
        public void online(String serviceId) {
            calls.add("online:" + serviceId);
        }

        @Override
        public void maintenance(String group, boolean enabled) {
            calls.add("maintenance:" + group + ":" + enabled);
        }

        @Override
        public void scale(String group, int target) {
            calls.add("scale:" + group + ":" + target);
        }

        @Override
        public void connect(String uuid, String service) {
            calls.add("connect:" + uuid + ":" + service);
        }

        @Override
        public void kick(String uuid, String reason) {
            calls.add("kick:" + uuid + ":" + reason);
        }

        private void check() {
            if (failure != null) {
                throw failure;
            }
        }
    }
}
