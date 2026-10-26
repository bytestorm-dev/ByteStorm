package dev.bytestorm.launcher.wizard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MongoUrisTest {

    @Test
    void appendsUserAndPasswordToAHostUri() {
        assertEquals("mongodb://admin:geheim@db:27017",
                MongoUris.withCredentials("mongodb://db:27017", "admin", "geheim"));
    }

    @Test
    void keepsPathAndQuery() {
        assertEquals("mongodb+srv://admin:geheim@cluster.example.com/cloud?retryWrites=true",
                MongoUris.withCredentials("mongodb+srv://cluster.example.com/cloud?retryWrites=true",
                        "admin", "geheim"));
    }

    @Test
    void encodesSpecialCharacters() {
        assertEquals("mongodb://a%40b:p%3Ass%2Fword%20%2B@db:27017",
                MongoUris.withCredentials("mongodb://db:27017", "a@b", "p:ss/word +"));
    }

    @Test
    void leavesAnUriWithCredentialsUntouched() {
        assertEquals("mongodb://existing:secret@db:27017",
                MongoUris.withCredentials("mongodb://existing:secret@db:27017", "other", "secret"));
    }

    @Test
    void withoutUsernameTheUriStaysUnchanged() {
        assertEquals("mongodb://db:27017", MongoUris.withCredentials("mongodb://db:27017", " ", "geheim"));
    }

    @Test
    void withoutPasswordOnlyTheUserIsAdded() {
        assertEquals("mongodb://admin@db:27017",
                MongoUris.withCredentials("mongodb://db:27017", "admin", null));
    }
}
