plugins {
    alias(libs.plugins.shadow)
}

// Die Plugin-Beschreibung trägt dieselbe Version wie das Release. Die Version
// ist explizit ein Task-Input, sonst gilt ein Versionswechsel als „up-to-date“
// und im Jar bliebe die alte Version stehen.
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("bungee.yml") {
        expand("version" to project.version)
    }
}

dependencies {
    compileOnly(libs.bungeecord.api)
    implementation(project(":bridge:bridge-common"))

    // BungeeCord bringt kein SLF4J mit; bridge-common loggt aber darüber.
    // Die JUL-Bindung leitet die Meldungen in die Bungee-Konsole.
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.jdk14)
}

tasks.shadowJar {
    archiveClassifier.set("")

    // §6.4: Protobuf muss im Plugin-Jar relocated werden, sonst Konflikte mit anderen Plugins.
    relocate("com.google.protobuf", "dev.bytestorm.libs.protobuf")
    relocate("org.redisson", "dev.bytestorm.libs.redisson")
    mergeServiceFiles()
}

// `jar` und `shadowJar` schreiben über archiveClassifier = "" unter demselben Namen
// nach build/libs. Liefen beide, überschrieb die dünne Klassen-Jar die Shadow-Jar —
// je nach Reihenfolge landete eine unvollständige Plugin-Jar in lib/ (§6.4).
tasks.jar {
    enabled = false
}
