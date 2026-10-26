// Die Version steht im Manifest: Der Updater vergleicht sie mit den Releases (§17).
tasks.jar {
    manifest {
        attributes["Implementation-Title"] = "ByteStorm"
        attributes["Implementation-Version"] = project.version
    }
}

dependencies {
    api(project(":api"))

    // Codec liegt in einem eigenen Modul, damit Bridges ihn ohne Mongo nutzen.
    api(project(":codec"))

    // Einzige erlaubte Infrastruktur (§1): Redisson + MongoDB.
    api(libs.redisson)
    api(libs.mongodb.driver.sync)
    api(libs.slf4j.api)

    // Logging-Setup (Konsole + rotierende Datei) lebt in core.
    implementation(libs.logback.classic)

    // en2do ist die Mongo-Persistenzschicht (§8); MongoProvider exponiert seine Repository-Typen.
    api(libs.en2do)

    // cloud.json (de)serialisieren (§21).
    implementation(libs.jackson.databind)
}
