package dev.bytestorm.launcher.wizard;

import dev.bytestorm.codec.ProtobufCodec;
import dev.bytestorm.codec.TypeRegistry;
import dev.bytestorm.core.config.MongoSettings;
import dev.bytestorm.core.config.RedisSettings;
import dev.bytestorm.core.mongo.MongoProvider;
import dev.bytestorm.core.redis.RedisProvider;

/**
 * Prüft die Verbindungen mit den echten Providern aus {@code core}.
 *
 * <p>Beide Verbindungen werden nach der Prüfung sofort wieder geschlossen, damit
 * der Wizard keine Ressourcen offen lässt.
 */
public final class DefaultConnectionTester implements ConnectionTester {

    @Override
    public void testMongo(MongoSettings settings) {
        try (MongoProvider mongo = MongoProvider.connect(settings)) {
            mongo.ping();
        }
    }

    @Override
    public void testRedis(RedisSettings settings) {
        ProtobufCodec codec = new ProtobufCodec(TypeRegistry.byteStormDefaults());
        try (RedisProvider redis = RedisProvider.connect(settings, codec)) {
            redis.ping();
        }
    }
}
