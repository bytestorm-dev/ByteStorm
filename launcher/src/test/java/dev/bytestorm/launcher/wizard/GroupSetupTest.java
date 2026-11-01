package dev.bytestorm.launcher.wizard;

import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupSetupTest {

    @Test
    void asksForAGroupAndBuildsTheDefinition() {
        ScriptedWizardIo io = new ScriptedWizardIo()
                .answer("Name", "Lobby")
                .answer("Typ", "SERVER")
                .answer("Plattform", "paper")
                .answer("Modus", "DYNAMIC")
                .answer("Min. Services", "1")
                .answer("Max. Services", "3")
                .answer("Spieler je Service", "100")
                .answer("Speicher", "2048")
                .answer("Fallback-Ziel", "j")
                .answer("Weitere Gruppe anlegen?", "n");

        List<ServiceGroup> groups = GroupSetup.ask(io);

        assertEquals(1, groups.size());
        ServiceGroup group = groups.get(0);
        assertEquals("Lobby", group.getName());
        assertEquals(ServiceType.SERVER, group.getType());
        assertEquals("paper", group.getPlatform());
        assertEquals(ServiceMode.DYNAMIC, group.getMode());
        assertEquals(1, group.getMinServices());
        assertEquals(3, group.getMaxServices());
        assertEquals(100, group.getMaxPlayers());
        assertEquals(2048, group.getMemory());
        assertTrue(group.getFallback());
        assertEquals(1, group.getFallbackPriority());
        assertEquals(List.of("Name", "Typ", "Plattform", "Modus", "Min. Services", "Max. Services",
                "Spieler je Service", "Speicher je Service", "Fallback-Ziel",
                "Weitere Gruppe anlegen?"), io.asked());
    }

    @Test
    void keepsAskingWhileTheUserWantsAnotherGroup() {
        ScriptedWizardIo io = new ScriptedWizardIo()
                .answer("Name", "Proxy", "Lobby")
                .answer("Typ", "PROXY", "SERVER")
                .answer("Plattform", "velocity", "paper")
                .answer("Modus", "STATIC", "DYNAMIC")
                .answer("Min. Services", "1", "1")
                .answer("Max. Services", "1", "3")
                // Spieler und Fallback fragt der Wizard nur für Server-Gruppen ab.
                .answer("Spieler je Service", "100")
                .answer("Speicher", "512", "2048")
                .answer("Fallback-Ziel", "n")
                .answer("Weitere Gruppe anlegen?", "j", "n");

        List<ServiceGroup> groups = GroupSetup.ask(io);

        assertEquals(List.of("Proxy", "Lobby"), groups.stream().map(ServiceGroup::getName).toList());
        ServiceGroup proxy = groups.get(0);
        assertEquals(ServiceType.PROXY, proxy.getType());
        assertEquals(ServiceMode.STATIC, proxy.getMode());
        assertEquals(512, proxy.getMemory());
        assertEquals(0, proxy.getMaxPlayers(), "ein Proxy zählt keine Spieler");
        assertFalse(proxy.getFallback());
        assertEquals(ServiceType.SERVER, groups.get(1).getType());
        assertFalse(groups.get(1).getFallback());
    }

    @Test
    void rejectsATakenNameWithinTheSameRun() {
        Questionnaire form = GroupSetup.form(List.of(group("Lobby")));

        assertNotNull(form.question(GroupSetup.NAME).validate("Lobby", Answers.empty()));
        assertNotNull(form.question(GroupSetup.NAME).validate("lobby", Answers.empty()));
        assertNull(form.question(GroupSetup.NAME).validate("Build", Answers.empty()));
    }

    @Test
    void rejectsNamesThatAreNoSuitableFolderNames() {
        Questionnaire form = GroupSetup.form(List.of());

        assertNotNull(form.question(GroupSetup.NAME).validate("Lobby 1", Answers.empty()));
        assertNotNull(form.question(GroupSetup.NAME).validate("", Answers.empty()));
        assertNull(form.question(GroupSetup.NAME).validate("Lobby_1", Answers.empty()));
    }

    @Test
    void requiresPlatformAndTypeToAgree() {
        Questionnaire form = GroupSetup.form(List.of());
        Answers server = Answers.empty().with(GroupSetup.TYPE, ServiceType.SERVER.name());
        Answers proxy = Answers.empty().with(GroupSetup.TYPE, ServiceType.PROXY.name());

        assertNotNull(form.question(GroupSetup.PLATFORM).validate("velocity", server));
        assertNull(form.question(GroupSetup.PLATFORM).validate("paper", server));
        assertNotNull(form.question(GroupSetup.PLATFORM).validate("paper", proxy));
        assertNull(form.question(GroupSetup.PLATFORM).validate("velocity", proxy));
    }

    @Test
    void aProxyOccupiesExactlyOneServiceAndMinNeverExceedsMax() {
        Questionnaire form = GroupSetup.form(List.of());
        Answers proxy = Answers.empty().with(GroupSetup.TYPE, ServiceType.PROXY.name());

        assertNotNull(form.question(GroupSetup.MAX).validate("2", proxy));
        assertNull(form.question(GroupSetup.MAX).validate("1", proxy));
        assertNotNull(form.question(GroupSetup.MIN).validate("5", Answers.empty().with(GroupSetup.MAX, "3")));
        assertNull(form.question(GroupSetup.MIN).validate("2", Answers.empty().with(GroupSetup.MAX, "3")));
        assertNull(form.question(GroupSetup.MIN).validate("2", Answers.empty().with(GroupSetup.MAX, "0")));
    }

    @Test
    void aProxyGroupSkipsThePlayerQuestion() {
        Questionnaire form = GroupSetup.form(List.of());

        assertFalse(form.question(GroupSetup.MAX_PLAYERS)
                .visible(Answers.empty().with(GroupSetup.TYPE, ServiceType.PROXY.name())));
        assertTrue(form.question(GroupSetup.MAX_PLAYERS)
                .visible(Answers.empty().with(GroupSetup.TYPE, ServiceType.SERVER.name())));
    }

    private static ServiceGroup group(String name) {
        return ServiceGroup.newBuilder().setName(name).build();
    }
}
