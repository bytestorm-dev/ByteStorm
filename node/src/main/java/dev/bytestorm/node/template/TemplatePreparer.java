package dev.bytestorm.node.template;

import dev.bytestorm.node.lifecycle.DirectoryPreparer;
import dev.bytestorm.node.lifecycle.ServiceDefinition;
import dev.bytestorm.node.lifecycle.ServicePreparer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Baut den Service-Ordner aus den Template-Ebenen auf (§10).
 *
 * <p>Die Ebenen aus {@link TemplateLayers} werden nacheinander angewendet,
 * spätere überschreiben frühere. Textdateien werden als UTF-8 erkannt und
 * erhalten die Platzhalter aus {@link TemplateContext}; alles andere wird
 * byteweise kopiert, damit Binärdateien unangetastet bleiben.
 *
 * <p>Dynamische Services bekommen ihre Dateien bei jedem Start neu,
 * statische nur beim ersten Anlegen ihres Ordners.
 *
 * <p>Das Forwarding-Secret (§14) reicht der Konstruktor herein, weil es nicht
 * aus anderen Zugangsdaten abgeleitet werden darf: Proxy und Backends müssen
 * denselben Wert aus {@code config/cloud.json} erhalten.
 */
@Slf4j
public final class TemplatePreparer implements ServicePreparer {

    /** Größere Dateien gelten als binär und werden nicht durchsucht. */
    private static final long MAX_TEXT_BYTES = 1024 * 1024;

    /**
     * Merkt in einem statischen Service-Ordner, dass die Templates schon einmal
     * angewendet wurden.
     *
     * <p>Früher entschied der bloße Inhalt des Ordners darüber. Damit galt der
     * Proxy als fertig vorbereitet, sobald die LuckPerms-Jar in {@code plugins/}
     * lag — er bekam nie sein {@code velocity.toml} und Velocity schrieb sich
     * seine eigene Vorgabe mit {@code player-info-forwarding-mode = "NONE"} und
     * einem eigenen Secret. Die Backends wiesen danach jede Verbindung ab.
     */
    public static final String PREPARED_MARKER = ".bytestorm-prepared";

    private final Path templatesRoot;
    private final String forwardingSecret;
    private final DirectoryPreparer cleanup = new DirectoryPreparer();

    /**
     * @param templatesRoot Ordner mit den Template-Ebenen
     */
    public TemplatePreparer(Path templatesRoot) {
        this(templatesRoot, null);
    }

    /**
     * @param templatesRoot    Ordner mit den Template-Ebenen
     * @param forwardingSecret Secret für den Platzhalter {@code %SECRET%}, darf {@code null} sein
     */
    public TemplatePreparer(Path templatesRoot, String forwardingSecret) {
        this.templatesRoot = Objects.requireNonNull(templatesRoot, "templatesRoot")
                .toAbsolutePath().normalize();
        this.forwardingSecret = forwardingSecret;
    }

    @Override
    public void prepare(ServiceDefinition definition) throws IOException {
        prepare(definition, 0);
    }

    @Override
    public void prepare(ServiceDefinition definition, int port) throws IOException {
        apply(definition, port, false);
    }

    /**
     * Wendet die Templates auf Befehl erneut an — auch auf einen statischen
     * Ordner, der schon vorbereitet ist. Nur die Dateien der Template-Ebenen
     * werden ersetzt; Jar-Dateien, Welten und eigene Konfigurationen bleiben.
     */
    @Override
    public void reapply(ServiceDefinition definition) throws IOException {
        apply(definition, 0, true);
    }

    @Override
    public void reapply(ServiceDefinition definition, int port) throws IOException {
        apply(definition, port, true);
    }

    private void apply(ServiceDefinition definition, int port, boolean force) throws IOException {
        Objects.requireNonNull(definition, "definition");
        Path directory = definition.directory();
        Files.createDirectories(directory);
        Path marker = directory.resolve(PREPARED_MARKER);
        if (!force && !definition.dynamic() && Files.isRegularFile(marker)) {
            // Statisch: der Ordner bleibt bestehen, Templates nur beim ersten Anlegen.
            return;
        }

        TemplateContext context = TemplateContext.of(definition, port, forwardingSecret);
        boolean applied = false;
        for (String layer : TemplateLayers.forGroup(definition.group())) {
            Path template = templatesRoot.resolve(layer).normalize();
            if (!template.startsWith(templatesRoot)) {
                log.warn("Template-Ebene '{}' liegt außerhalb von {}", layer, templatesRoot);
                continue;
            }
            if (Files.isDirectory(template)) {
                applyLayer(template, directory, context);
                applied = true;
            }
        }
        if (applied) {
            // Erst nach vollständigem Kopieren: ein Abbruch soll den nächsten Start
            // die Templates erneut anwenden lassen.
            Files.writeString(marker, "", StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }
    }

    @Override
    public void cleanup(ServiceDefinition definition) throws IOException {
        cleanup.cleanup(definition);
    }

    private static void applyLayer(Path template, Path directory, TemplateContext context) throws IOException {
        List<Path> files;
        try (var walk = Files.walk(template)) {
            files = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> template.relativize(path).toString()))
                    .toList();
        }

        TemplateIgnore ignore = TemplateIgnore.read(template);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks = new ArrayList<>();
            for (Path source : files) {
                Path relative = template.relativize(source);
                if (relative.toString().equals(".bytestorm-ignore") || ignore.matches(relative)) {
                    continue;
                }
                tasks.add(executor.submit(() -> {
                    copy(source, directory.resolve(relative), context);
                    return null;
                }));
            }
            for (Future<?> task : tasks) {
                try {
                    task.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Template-Kopie abgebrochen", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException io) {
                        throw io;
                    }
                    if (cause instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new IOException(cause);
                }
            }
        }
    }

    private static void copy(Path source, Path target, TemplateContext context) throws IOException {
        Files.createDirectories(target.getParent());
        byte[] bytes = Files.readAllBytes(source);
        String text = text(bytes);
        if (text == null) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        Files.write(target, context.apply(text).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        Files.setLastModifiedTime(target, Files.getLastModifiedTime(source));
    }

    private static String text(byte[] bytes) {
        if (bytes.length > MAX_TEXT_BYTES) {
            return null;
        }
        for (byte value : bytes) {
            if (value == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

}
