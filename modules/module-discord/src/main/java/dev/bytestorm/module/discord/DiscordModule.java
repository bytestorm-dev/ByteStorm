package dev.bytestorm.module.discord;

import dev.bytestorm.api.ByteStormApi;
import dev.bytestorm.api.CloudModule;
import dev.bytestorm.api.event.Subscription;
import dev.bytestorm.api.module.ModuleContext;
import dev.bytestorm.proto.GroupHealthChanged;

import java.util.Objects;
import java.util.function.Function;

/**
 * Discord-Modul der Cloud (§16, §12.5): meldet, wenn eine Gruppe keine Neustarts
 * mehr bekommt.
 *
 * <p>Das Modul arbeitet ausschließlich über die öffentliche API: Es abonniert
 * {@link GroupHealthChanged} am Event-Bus und schickt die Meldung an einen
 * Webhook. Ohne Abonnement gibt es keinen Discord-Verkehr, deshalb entsteht der
 * Client erst in {@link #onEnable()}.
 *
 * <p>Damit niemand Meldungen verpasst, während niemand hinsieht, ist
 * {@code DEGRADED} immer eine Nachricht; die Entwarnung nach einem manuellen
 * Start lässt sich mit {@code notifyOnRecovery=false} abschalten.
 */
public final class DiscordModule implements CloudModule {

    /** Modulname, unter dem die Cloud es führt. */
    public static final String NAME = "discord";

    private final Function<DiscordSettings, DiscordNotifier> notifiers;

    private ModuleContext context;
    private DiscordSettings settings;
    private DiscordNotifier notifier;
    private Subscription subscription;

    /**
     * Modul für die Cloud: Meldungen gehen an den konfigurierten Webhook.
     */
    public DiscordModule() {
        this(WebhookNotifier::new);
    }

    /**
     * @param notifiers erzeugt den Zusteller, in Tests ein Sammler
     */
    DiscordModule(Function<DiscordSettings, DiscordNotifier> notifiers) {
        this.notifiers = Objects.requireNonNull(notifiers, "notifiers");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    /**
     * Prüft die Einstellungen, bevor irgendetwas abonniert wird: Ohne Webhook
     * bleibt das Modul aus, statt Meldungen ins Leere zu schicken.
     *
     * @param context Zugriff auf API, Datenverzeichnis und Logger
     * @throws IllegalArgumentException wenn die Einstellungen unbrauchbar sind
     */
    @Override
    public void onLoad(ModuleContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.settings = DiscordSettings.from(context.settings());
        context.logger().info("Discord-Modul vorbereitet ({})", settings);
    }

    /**
     * Abonniert die Gesundheitswechsel der Gruppen. Wiederholte Aufrufe sind
     * wirkungslos.
     *
     * @throws IllegalStateException wenn die API nicht installiert ist
     */
    @Override
    public synchronized void onEnable() {
        if (subscription != null) {
            return;
        }
        ByteStormApi api = context.api();
        notifier = notifiers.apply(settings);
        subscription = api.events().subscribe(GroupHealthChanged.class, event -> notifyGroup(event));
        context.logger().info("Discord-Modul aktiv ({})", settings);
    }

    /**
     * Beendet das Abonnement; danach gehen keine Meldungen mehr raus.
     */
    @Override
    public synchronized void onDisable() {
        Subscription running = subscription;
        subscription = null;
        notifier = null;
        if (running != null) {
            running.close();
            context.logger().info("Discord-Modul gestoppt");
        }
    }

    /**
     * @return {@code true}, solange das Modul Meldungen abonniert hat
     */
    public synchronized boolean isRunning() {
        return subscription != null;
    }

    private void notifyGroup(GroupHealthChanged event) {
        DiscordNotifier target;
        synchronized (this) {
            target = notifier;
        }
        if (target == null || context == null) {
            return;
        }
        String text = DiscordMessages.text(event, settings);
        if (text == null) {
            return;
        }
        if (target.send(text)) {
            context.logger().info("Discord-Meldung zugestellt: {}", text);
        }
    }
}
