package dev.bytestorm.plugins.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.bytestorm.plugins.proxy.text.Components;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Verbindet {@code /cloud} mit Velocity.
 *
 * <p>Die Klasse ist absichtlich dünn: Sie registriert den Befehl mit seinen
 * Aliassen, führt jede Eingabe auf einem eigenen Virtual Thread aus — die
 * Anfragen an das REST-Modul blockieren — und schickt die fertigen Zeilen an den
 * Absender. Was ein Kommando tut, entscheidet {@link CloudCommand}.
 */
public final class CloudCommandRouter implements SimpleCommand {

    /** Wie lange das Abschalten auf laufende Anfragen wartet. */
    private static final long SHUTDOWN_SECONDS = 2;

    private final Logger logger;
    private final BiFunction<String, CommandSource, List<Reply>> handler;
    private final Function<CommandSource, List<String>> suggestions;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * @param logger      Plugin-Logger
     * @param handler     führt eine Zeile aus und liefert Antwortzeilen
     * @param suggestions liefert die Vervollständigung für einen Absender
     */
    public CloudCommandRouter(Logger logger,
                              BiFunction<String, CommandSource, List<Reply>> handler,
                              Function<CommandSource, List<String>> suggestions) {
        this.logger = logger;
        this.handler = handler;
        this.suggestions = suggestions;
    }

    /**
     * Meldet den Befehl samt Aliassen an.
     *
     * @param server Proxy
     * @param plugin Plugin-Instanz, gehört zum {@code CommandMeta}
     */
    public void register(ProxyServer server, Object plugin) {
        server.getCommandManager().register(server.getCommandManager()
                .metaBuilder(CloudCommand.NAME)
                .aliases(CloudCommand.ALIASES.toArray(String[]::new))
                .plugin(plugin)
                .build(), this);
    }

    /**
     * Meldet den Befehl wieder ab. Wiederholte Aufrufe sind wirkungslos.
     *
     * @param server Proxy
     */
    public void unregister(ProxyServer server) {
        server.getCommandManager().unregister(CloudCommand.NAME);
    }

    /**
     * Beendet die Worker und wartet kurz auf laufende Anfragen.
     */
    public void close() {
        workers.shutdownNow();
        try {
            workers.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Führt den Befehl aus. Die Antworten kommen asynchron, damit ein langsames
     * REST-Modul den Proxy-Thread nicht blockiert.
     *
     * @param invocation Aufruf mit Absender und Argumenten
     */
    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String line = String.join(" ", invocation.arguments());
        workers.execute(() -> {
            List<Reply> replies;
            try {
                replies = handler.apply(line, source);
            } catch (RuntimeException e) {
                logger.warn("/cloud fehlgeschlagen: {}", e.toString());
                return;
            }
            for (Reply reply : replies) {
                if (reply == null || reply.text().isEmpty()) {
                    continue;
                }
                source.sendMessage(reply.clickable()
                        ? Components.of(reply.text(), reply.command())
                        : Components.of(reply.text()));
            }
        });
    }

    /**
     * @param invocation Aufruf mit den bisher getippten Argumenten
     * @return Vorschläge, gefiltert nach dem letzten Wort
     */
    @Override
    public List<String> suggest(Invocation invocation) {
        String[] arguments = invocation.arguments();
        List<String> candidates = suggestions.apply(invocation.source());
        if (arguments.length <= 1) {
            String prefix = arguments.length == 0 ? "" : arguments[0].toLowerCase(Locale.ROOT);
            return candidates.stream()
                    .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
