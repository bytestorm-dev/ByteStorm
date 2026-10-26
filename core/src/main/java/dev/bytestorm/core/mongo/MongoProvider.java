package dev.bytestorm.core.mongo;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import dev.bytestorm.core.config.MongoSettings;
import eu.koboo.en2do.MongoManager;
import eu.koboo.en2do.SettingsBuilder;
import eu.koboo.en2do.repository.Repository;
import org.bson.Document;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Verwaltet die MongoDB-Verbindung über en2do (§8).
 *
 * <p>en2do erwartet den Datenbanknamen im Connection-String; {@link MongoSettings}
 * führt ihn separat, deshalb wird er beim Aufbau ergänzt. Indizes entstehen aus
 * den Annotations der Entities, sobald das jeweilige Repository erzeugt wird.
 */
public final class MongoProvider implements AutoCloseable {

    private final MongoManager manager;
    private final String redactedTarget;

    private MongoProvider(MongoManager manager, String redactedTarget) {
        this.manager = manager;
        this.redactedTarget = redactedTarget;
    }

    /**
     * Baut den Client, prüft die Verbindung und gibt den Provider zurück.
     *
     * @param settings Verbindungsdaten
     * @return verbundener Provider
     * @throws MongoUnavailableException wenn der Server nicht erreichbar ist
     */
    public static MongoProvider connect(MongoSettings settings) {
        Objects.requireNonNull(settings, "settings");

        MongoManager manager = new MongoManager(new SettingsBuilder()
                .connectionString(connectionString(settings))
                .clientConfigurator(builder -> builder
                        .applyToClusterSettings(b -> b.serverSelectionTimeout(10, TimeUnit.SECONDS))
                        .applyToSocketSettings(b -> b
                                .connectTimeout(5, TimeUnit.SECONDS)
                                .readTimeout(10, TimeUnit.SECONDS)))
                .build());

        MongoProvider provider = new MongoProvider(manager, settings.toString());
        try {
            provider.ping();
        } catch (RuntimeException e) {
            manager.close();
            throw e;
        }
        return provider;
    }

    /**
     * Setzt den konfigurierten Datenbanknamen in den Connection-String ein.
     *
     * @param settings Verbindungsdaten
     * @return Connection-String inklusive Datenbank, z. B. {@code mongodb://host:27017/bytestorm}
     */
    static String connectionString(MongoSettings settings) {
        String uri = settings.uri();
        int schemeEnd = uri.indexOf("://");
        if (schemeEnd < 0) {
            return uri;
        }
        int queryStart = uri.indexOf('?', schemeEnd + 3);
        String base = queryStart < 0 ? uri : uri.substring(0, queryStart);
        String query = queryStart < 0 ? "" : uri.substring(queryStart);

        int pathStart = base.indexOf('/', schemeEnd + 3);
        String authority = pathStart < 0 ? base : base.substring(0, pathStart);
        return authority + "/" + settings.database() + query;
    }

    /**
     * Erzeugt ein en2do-Repository (Interface mit {@code @Collection}).
     *
     * @param repositoryClass Repository-Interface
     * @param <E>             Entity-Typ
     * @param <ID>            ID-Typ
     * @param <R>             Repository-Typ
     * @return das erzeugte Repository
     */
    public <E, ID, R extends Repository<E, ID>> R repository(Class<R> repositoryClass) {
        return manager.create(repositoryClass);
    }

    /**
     * Prüft die Verbindung mit einem {@code ping}.
     *
     * @throws MongoUnavailableException wenn der Server nicht antwortet
     */
    public void ping() {
        try {
            manager.getMongoDatabase().runCommand(new Document("ping", 1));
        } catch (MongoException e) {
            // redactedTarget statt der rohen URI: das Passwort darf nie im Log landen (§20).
            throw new MongoUnavailableException(
                    "MongoDB nicht erreichbar (" + redactedTarget + "): " + e.getMessage(), e);
        }
    }

    /**
     * @return die von en2do verwaltete Datenbank
     */
    public MongoDatabase database() {
        return manager.getMongoDatabase();
    }

    /**
     * @return der native Client, z. B. für Healthchecks
     */
    public MongoClient client() {
        return manager.getMongoClient();
    }

    /**
     * @return der en2do-Manager (für Tests und Diagnose)
     */
    public MongoManager manager() {
        return manager;
    }

    @Override
    public void close() {
        manager.close();
    }
}
