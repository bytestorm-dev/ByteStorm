plugins {
    alias(libs.plugins.shadow)
}

// Die Plugin-Beschreibung trägt dieselbe Version wie das Release. Die Version ist
// explizit ein Task-Input, sonst gilt ein Versionswechsel als „up-to-date“ und im
// Jar bliebe die alte Version stehen.
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("velocity-plugin.json") {
        expand("version" to project.version)
    }
}

dependencies {
    // Stellt der Proxy; darf nicht ins Plugin-Jar.
    compileOnly(libs.velocity.api)
    compileOnly(libs.slf4j.api)

    // Konfiguration und Sprachdateien (§16). Wie bei den Bridges relocatet, damit
    // ein anderes Proxy-Plugin mit eigener Jackson-Version nicht kollidiert.
    implementation(libs.jackson.databind)

    testRuntimeOnly(libs.logback.classic)
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("com.fasterxml.jackson", "dev.bytestorm.libs.jackson")
    mergeServiceFiles()
}

// `jar` und `shadowJar` schrieben sonst unter demselben Namen nach build/libs —
// je nach Reihenfolge überschriebe die dünne Klassen-Jar die Shadow-Jar.
tasks.jar {
    enabled = false
}
