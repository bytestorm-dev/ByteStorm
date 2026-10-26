package dev.bytestorm.core.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hält die aktive Konfiguration und erlaubt ein Neuladen zur Laufzeit.
 *
 * <p>Nur unkritische Felder werden übernommen. Änderungen an Mongo, Redis, Runtime
 * oder Docker-Host benötigen einen Neustart, weil die Clients beim Start erzeugt
 * werden; ein Reload mit solchen Änderungen wird abgelehnt (§3.1).
 */
public final class ConfigService {

    private final Path file;
    private final ConfigLoader loader;
    private final AtomicReference<CloudConfig> current;

    /**
     * @param file    Pfad zu {@code cloud.json}
     * @param loader  Loader für erneutes Einlesen
     * @param initial bereits geladene Konfiguration
     */
    public ConfigService(Path file, ConfigLoader loader, CloudConfig initial) {
        this.file = Objects.requireNonNull(file, "file");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    /**
     * Lädt die Konfiguration aus der Datei und erstellt den Service.
     *
     * @param file Pfad zu {@code cloud.json}
     * @return Service mit geladener Konfiguration
     * @throws ConfigException wenn die Datei ungültig ist
     */
    public static ConfigService load(Path file) throws ConfigException {
        ConfigLoader loader = new ConfigLoader();
        return new ConfigService(file, loader, loader.load(file));
    }

    /**
     * @return die aktuell aktive Konfiguration
     */
    public CloudConfig current() {
        return current.get();
    }

    /**
     * Liest die Datei erneut und übernimmt Änderungen, die keinen Neustart brauchen.
     *
     * @return die neue Konfiguration
     * @throws ConfigException wenn die Datei ungültig ist oder Verbindungsdaten geändert wurden
     */
    public CloudConfig reload() throws ConfigException {
        CloudConfig next = loader.load(file);
        CloudConfig previous = current.get();

        if (!previous.mongo().equals(next.mongo())) {
            throw restartRequired("mongo");
        }
        if (!previous.redis().equals(next.redis())) {
            throw restartRequired("redis");
        }
        if (previous.runtime() != next.runtime()) {
            throw restartRequired("runtime");
        }
        if (!previous.docker().equals(next.docker())) {
            throw restartRequired("docker");
        }
        if (!previous.proxy().equals(next.proxy())) {
            throw restartRequired("proxy.forwardingSecret");
        }

        current.set(next);
        return next;
    }

    /**
     * Prüft eine geänderte Konfiguration, schreibt sie atomar nach {@code cloud.json}
     * und macht sie zur aktiven Konfiguration.
     *
     * @param next neue Konfiguration
     * @return die übernommene Konfiguration
     * @throws ConfigException wenn die Konfiguration ungültig ist oder nicht geschrieben werden kann
     */
    public CloudConfig update(CloudConfig next) throws ConfigException {
        Objects.requireNonNull(next, "next");
        loader.validate(next);
        try {
            ConfigWriter.write(file, next);
        } catch (IOException e) {
            throw new ConfigException("Konfiguration konnte nicht geschrieben werden: "
                    + file.toAbsolutePath(), e);
        }
        current.set(next);
        return next;
    }

    /**
     * Stellt sicher, dass ein Forwarding-Secret existiert (§14). Ein einmal
     * gesetztes Secret bleibt unverändert, sonst wären laufende Proxies sofort
     * abgekoppelt; nur ein fehlendes wird erzeugt und gespeichert.
     *
     * @return die Konfiguration mit gesetztem Secret
     * @throws ConfigException wenn das erzeugte Secret nicht gespeichert werden kann
     */
    public CloudConfig ensureForwardingSecret() throws ConfigException {
        CloudConfig config = current.get();
        if (config.proxy().hasSecret()) {
            return config;
        }
        return update(config.withForwardingSecret(ProxySettings.generateSecret()));
    }

    private static ConfigException restartRequired(String field) {
        return new ConfigException("Änderung an '" + field + "' erfordert einen Neustart der Cloud");
    }
}
