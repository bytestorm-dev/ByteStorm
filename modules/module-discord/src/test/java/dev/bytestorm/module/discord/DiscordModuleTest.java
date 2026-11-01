package dev.bytestorm.module.discord;

import com.google.protobuf.Message;
import dev.bytestorm.api.ByteStormApi;
import dev.bytestorm.api.event.Events;
import dev.bytestorm.api.event.Subscription;
import dev.bytestorm.api.module.ModuleContext;
import dev.bytestorm.proto.GroupHealthChanged;
import dev.bytestorm.proto.GroupState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordModuleTest {

    private static final String WEBHOOK = "https://discord.com/api/webhooks/123/abc";

    @TempDir
    Path temp;

    private final List<String> sent = new ArrayList<>();
    private final FakeEvents events = new FakeEvents();

    @Test
    void notifiesWhenAGroupGoesDegraded() {
        DiscordModule module = module(Map.of("webhookUrl", WEBHOOK));
        module.onEnable();
        try {
            events.fire(degraded());

            assertEquals(1, sent.size(), "eine Sperre erzeugt genau eine Meldung");
            assertTrue(sent.get(0).contains("Lobby"), sent.get(0));
            assertTrue(sent.get(0).contains("DEGRADED"), sent.get(0));
            assertTrue(sent.get(0).contains("Start-Timeout"), "die Ursache steht in der Meldung");
            assertTrue(sent.get(0).contains("manuell starten"), sent.get(0));
        } finally {
            module.onDisable();
        }
    }

    @Test
    void reportsTheRecoveryOnlyWhenConfigured() {
        DiscordModule quiet = module(Map.of("webhookUrl", WEBHOOK, "notifyOnRecovery", "false"));
        quiet.onEnable();
        try {
            events.fire(healthy());
        } finally {
            quiet.onDisable();
        }
        assertTrue(sent.isEmpty(), "Entwarnung ist abschaltbar");

        DiscordModule talking = module(Map.of("webhookUrl", WEBHOOK));
        talking.onEnable();
        try {
            events.fire(healthy());
        } finally {
            talking.onDisable();
        }
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("läuft wieder"), sent.get(0));
    }

    @Test
    void ignoresStatesThatAreNotAnAlert() {
        DiscordModule module = module(Map.of("webhookUrl", WEBHOOK));
        module.onEnable();
        try {
            events.fire(GroupHealthChanged.newBuilder()
                    .setGroup("Lobby").setState(GroupState.MAINTENANCE).build());
        } finally {
            module.onDisable();
        }

        assertTrue(sent.isEmpty(), "Wartungsmodus ist keine Störung");
    }

    @Test
    void stopsListeningWhenDisabled() {
        DiscordModule module = module(Map.of("webhookUrl", WEBHOOK));
        module.onEnable();
        assertTrue(module.isRunning());

        module.onDisable();
        events.fire(degraded());

        assertFalse(module.isRunning());
        assertTrue(events.closed, "das Abonnement muss beendet sein");
        assertTrue(sent.isEmpty(), "nach dem Stopp geht nichts mehr raus");
    }

    @Test
    void requiresAnHttpsWebhook() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> DiscordSettings.from(Map.of()));
        assertTrue(missing.getMessage().contains("webhookUrl"), missing.getMessage());

        IllegalArgumentException insecure = assertThrows(IllegalArgumentException.class,
                () -> DiscordSettings.from(Map.of("webhookUrl", "http://discord.com/api/webhooks/1/x")));
        assertTrue(insecure.getMessage().contains("https"), insecure.getMessage());

        IllegalArgumentException flag = assertThrows(IllegalArgumentException.class,
                () -> DiscordSettings.from(Map.of("webhookUrl", WEBHOOK, "notifyOnRecovery", "vielleicht")));
        assertTrue(flag.getMessage().contains("notifyOnRecovery"), flag.getMessage());
    }

    @Test
    void theWebhookTokenNeverAppearsInTheSettingsText() {
        DiscordSettings settings = DiscordSettings.from(Map.of("webhookUrl", WEBHOOK));

        assertFalse(settings.toString().contains("abc"), "der Token darf nicht im Log landen: " + settings);
    }

    @Test
    void thePayloadEscapesTextAndAllowsNoMentions() {
        String payload = WebhookNotifier.payload("Gruppe \"Lobby\"\n@everyone");

        assertTrue(payload.contains("\\\"Lobby\\\""), payload);
        assertTrue(payload.contains("\\n"), payload);
        assertTrue(payload.contains("\"allowed_mentions\":{\"parse\":[]}"), payload);
    }

    private DiscordModule module(Map<String, String> settings) {
        DiscordModule module = new DiscordModule(ignored -> text -> {
            sent.add(text);
            return true;
        });
        module.onLoad(new FakeContext(events, settings));
        return module;
    }

    private static GroupHealthChanged degraded() {
        return GroupHealthChanged.newBuilder()
                .setGroup("Lobby")
                .setState(GroupState.DEGRADED)
                .setService("Lobby-1")
                .setReason("Start-Timeout")
                .build();
    }

    private static GroupHealthChanged healthy() {
        return GroupHealthChanged.newBuilder()
                .setGroup("Lobby")
                .setState(GroupState.HEALTHY)
                .build();
    }

    /** Event-Bus, der nur das eine Abonnement kennt und auf Zuruf feuert. */
    private static final class FakeEvents implements Events {

        private Consumer<Object> listener;
        private Predicate<Object> filter = event -> true;
        private boolean closed;

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Message> Subscription subscribe(Class<T> type, Predicate<T> filter, Consumer<T> listener) {
            assertEquals(GroupHealthChanged.class, type, "das Modul hört nur auf Gesundheitswechsel");
            this.filter = (Predicate<Object>) (Predicate<?>) filter;
            this.listener = (Consumer<Object>) (Consumer<?>) listener;
            this.closed = false;
            return new Subscription() {
                @Override
                public void unsubscribe() {
                    closed = true;
                    FakeEvents.this.listener = null;
                }

                @Override
                public boolean isActive() {
                    return !closed;
                }
            };
        }

        void fire(GroupHealthChanged event) {
            if (listener != null && filter.test(event)) {
                listener.accept(event);
            }
        }
    }

    private static final class FakeContext implements ModuleContext {

        private final ByteStormApi api;
        private final Map<String, String> settings;

        private FakeContext(Events events, Map<String, String> settings) {
            this.settings = settings;
            this.api = new ByteStormApi() {
                @Override
                public dev.bytestorm.api.service.Services services() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public dev.bytestorm.api.player.Players players() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public dev.bytestorm.api.group.Groups groups() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Events events() {
                    return events;
                }

                @Override
                public dev.bytestorm.api.template.Templates templates() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public dev.bytestorm.api.module.Modules modules() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public ByteStormApi api() {
            return api;
        }

        @Override
        public Path dataDirectory() {
            return Path.of("build/tmp/discord");
        }

        @Override
        public Logger logger() {
            return LoggerFactory.getLogger("bytestorm.module.discord.test");
        }

        @Override
        public Map<String, String> settings() {
            return settings;
        }
    }
}
