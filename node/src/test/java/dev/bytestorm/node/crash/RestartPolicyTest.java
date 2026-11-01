package dev.bytestorm.node.crash;

import dev.bytestorm.core.config.CrashSettings;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartPolicyTest {

    @Test
    void followsTheConfiguredBackoffAndStopsAfterTheLastAttempt() {
        RestartPolicy policy = new RestartPolicy(new CrashSettings(3, List.of(5, 15, 60)));

        assertEquals(Duration.ofSeconds(5), policy.nextBackoff().orElseThrow());
        assertEquals(Duration.ofSeconds(15), policy.nextBackoff().orElseThrow());
        assertTrue(policy.nextBackoff().isEmpty(),
                "der dritte Absturz in Folge beendet die Neustarts");
        assertTrue(policy.exhausted());
        assertEquals(3, policy.attempts());
    }

    @Test
    void repeatsTheLastBackoffBeyondTheList() {
        RestartPolicy policy = new RestartPolicy(new CrashSettings(4, List.of(5, 15)));

        policy.nextBackoff();
        policy.nextBackoff();
        assertEquals(Duration.ofSeconds(15), policy.nextBackoff().orElseThrow());
    }

    @Test
    void resetsOnlyAfterTheOnlineTime() {
        RestartPolicy policy = new RestartPolicy(new CrashSettings(3, List.of(5)));
        policy.nextBackoff();
        policy.nextBackoff();

        assertFalse(policy.resetIfOnlineFor(Duration.ofMinutes(4)));
        assertEquals(2, policy.attempts());

        assertTrue(policy.resetIfOnlineFor(Duration.ofMinutes(5)));
        assertEquals(0, policy.attempts());
        assertFalse(policy.exhausted());
    }

    @Test
    void manualResetClearsAttempts() {
        RestartPolicy policy = new RestartPolicy(new CrashSettings(3, List.of(5)));
        policy.nextBackoff();

        policy.reset();

        assertEquals(0, policy.attempts());
    }

    @Test
    void usesTheDefaultsForAZeroConfiguration() {
        RestartPolicy policy = new RestartPolicy(new CrashSettings(0, null));

        assertEquals(Duration.ofSeconds(5), policy.nextBackoff().orElseThrow());
        assertEquals(Duration.ofSeconds(15), policy.nextBackoff().orElseThrow());
        assertTrue(policy.nextBackoff().isEmpty());
    }
}
