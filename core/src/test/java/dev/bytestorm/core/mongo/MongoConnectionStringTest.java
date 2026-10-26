package dev.bytestorm.core.mongo;

import dev.bytestorm.core.config.MongoSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MongoConnectionStringTest {

    @Test
    void appendsConfiguredDatabase() {
        MongoSettings settings = new MongoSettings("mongodb://localhost:27017", "bytestorm");

        assertEquals("mongodb://localhost:27017/bytestorm", MongoProvider.connectionString(settings));
    }

    @Test
    void replacesExistingDatabaseAndKeepsOptions() {
        MongoSettings settings = new MongoSettings("mongodb://user:pass@host:27017/other?retryWrites=true", "bytestorm");

        assertEquals("mongodb://user:pass@host:27017/bytestorm?retryWrites=true",
                MongoProvider.connectionString(settings));
    }

    @Test
    void supportsSrvScheme() {
        MongoSettings settings = new MongoSettings("mongodb+srv://cluster.example.com", "bytestorm");

        assertEquals("mongodb+srv://cluster.example.com/bytestorm", MongoProvider.connectionString(settings));
    }

    @Test
    void appendsOptionsWithoutDatabase() {
        MongoSettings settings = new MongoSettings("mongodb://localhost:27017?directConnection=true", "bytestorm");

        assertEquals("mongodb://localhost:27017/bytestorm?directConnection=true",
                MongoProvider.connectionString(settings));
    }
}
