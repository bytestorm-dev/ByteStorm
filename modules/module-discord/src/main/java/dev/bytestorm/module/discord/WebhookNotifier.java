package dev.bytestorm.module.discord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Schickt die Meldung als JSON an den Discord-Webhook.
 *
 * <p>Ein ausgefallener Webhook ist kein Cloud-Fehler: {@link #send(String)}
 * meldet nur {@code false} und schreibt eine Warnung. Damit hängt die
 * Betriebsbereitschaft der Cloud nicht an Discord.
 */
final class WebhookNotifier implements DiscordNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final URI webhook;
    private final HttpClient client;

    WebhookNotifier(DiscordSettings settings) {
        this.webhook = settings.webhook();
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public boolean send(String text) {
        HttpRequest request = HttpRequest.newBuilder(webhook)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(text), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 == 2) {
                return true;
            }
            log.warn("Discord-Webhook antwortet mit {} — Meldung nicht zugestellt", response.statusCode());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            log.warn("Discord-Benachrichtigung nicht zustellbar: {}", e.toString());
            return false;
        }
    }

    /**
     * Baut den Request-Body. {@code allowed_mentions.parse = []} verhindert, dass
     * ein Gruppen- oder Service-Name Spieler oder Rollen anpingt.
     *
     * @param content Nachricht
     * @return JSON-Body
     */
    static String payload(String content) {
        return "{\"content\":\"" + escape(content) + "\",\"allowed_mentions\":{\"parse\":[]}}";
    }

    private static String escape(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.toString();
    }
}
