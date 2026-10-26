package dev.bytestorm.node.runtime;

import dev.bytestorm.proto.LogLine;

/**
 * Verteilt Logzeilen eines Services, produktiv gebatcht über
 * {@code bs:log:<service>} (§7, §12.2).
 */
@FunctionalInterface
public interface LogPublisher extends AutoCloseable {

    /**
     * @param line Logzeile
     */
    void publish(LogLine line);

    @Override
    default void close() {
        // Publisher ohne Puffer müssen nichts aufräumen.
    }
}
