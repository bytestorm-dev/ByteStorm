plugins {
    alias(libs.plugins.shadow)
}

// InvUI liegt nicht auf Maven Central.
repositories {
    maven("https://repo.xenondevs.xyz/releases")
}

// Die Plugin-Beschreibung trägt dieselbe Version wie das Release. Die Version
// ist explizit ein Task-Input, sonst gilt ein Versionswechsel als „up-to-date“
// und im Jar bliebe die alte Version stehen.
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

dependencies {
    // Stellt der Paper-Server; darf nicht ins Plugin-Jar.
    compileOnly(libs.paper.api)

    // Proto-Typen (ServiceInfo, PlayerInfo) und die Redis-Serialisierung.
    implementation(project(":api"))
    implementation(project(":codec"))
    implementation(libs.redisson)

    // Auswahlmenü, wenn eine Gruppe mehr als einen Service hat.
    implementation(libs.invui)
}

tasks.shadowJar {
    archiveClassifier.set("")

    // Dieselben Relocations wie bei der Bridge: Ein zweites Plugin mit eigener
    // Protobuf- oder Redisson-Version darf nicht kollidieren (§6.4).
    relocate("com.google.protobuf", "dev.bytestorm.lobby.libs.protobuf")
    relocate("org.redisson", "dev.bytestorm.lobby.libs.redisson")
    // InvUI liegt in vielen Plugins in einer anderen Version; relocate hält sie auseinander.
    relocate("xyz.xenondevs.invui", "dev.bytestorm.lobby.libs.invui")
    mergeServiceFiles()
}

// `jar` und `shadowJar` schrieben sonst unter demselben Namen nach build/libs —
// je nach Reihenfolge überschriebe die dünne Klassen-Jar die Shadow-Jar.
tasks.jar {
    enabled = false
}
