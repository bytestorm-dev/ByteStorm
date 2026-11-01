package dev.bytestorm.plugins.proxy.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudPermissionsTest {

    @Test
    void theExactNodeAllowsExactlyOneCommand() {
        CloudPermissions permissions = granted("bytestorm.command.service.restart");

        assertTrue(permissions.can("service.restart"));
        assertFalse(permissions.can("service.stop"));
        assertFalse(permissions.can("player.kick"));
    }

    @Test
    void aGroupWildcardCoversItsSubcommands() {
        CloudPermissions permissions = granted("bytestorm.command.player.*");

        assertTrue(permissions.can("player.kick"));
        assertTrue(permissions.can("player.connect"));
        assertTrue(permissions.can("player.list"));
        assertFalse(permissions.can("service.stop"), "die Spieler-Wildcard deckt keine Services ab");
    }

    @Test
    void aLeafWildcardCoversTheSameCommandInEveryGroup() {
        CloudPermissions permissions = granted("bytestorm.command.kick.*");

        assertTrue(permissions.can("player.kick"),
                "bytestorm.command.kick.* ist die Node der Kick-Fähigkeit");
        assertFalse(permissions.can("player.connect"));
    }

    @Test
    void theGlobalWildcardAllowsEverything() {
        CloudPermissions permissions = granted("bytestorm.command.*");

        assertTrue(permissions.can("status"));
        assertTrue(permissions.can("service.restart-crashed"));
        assertTrue(permissions.can("player.kick"));
        assertTrue(permissions.can("tablist"));
    }

    @Test
    void unrelatedNodesGrantNothing() {
        CloudPermissions permissions = granted("bytestorm.command.player.kick",
                "bytestorm.cloud.command");

        assertFalse(permissions.can("player.connect"), "die exakte Node deckt keine Nachbarn ab");
        assertFalse(permissions.can("service.stop"), "fremde Nodes zählen nicht");
        assertFalse(new CloudPermissions(node -> false).can("status"));
    }

    @Test
    void candidatesGoFromSpecificToBroad() {
        assertEquals(List.of(
                        "bytestorm.command.player.kick",
                        "bytestorm.command.player.*",
                        "bytestorm.command.kick.*",
                        "bytestorm.command.*"),
                CloudPermissions.candidates("player.kick"));
        assertEquals(List.of(
                        "bytestorm.command.status",
                        "bytestorm.command.status.*",
                        "bytestorm.command.*"),
                CloudPermissions.candidates("status"));
        assertEquals("bytestorm.command.service.logs",
                CloudPermissions.node("service.logs"));
    }

    @Test
    void allowAllIgnoresThePath() {
        assertTrue(CloudPermissions.allowAll().can("irgendwas"));
    }

    private static CloudPermissions granted(String... nodes) {
        Set<String> allowed = Set.of(nodes);
        return new CloudPermissions(allowed::contains);
    }
}
