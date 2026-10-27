plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    // §18: Das Modul spricht ausschließlich über die öffentlichen api-Interfaces
    // mit der Cloud; Redisson und Mongo kennt es nicht.
    implementation(project(":api"))

    // Eingebetteter HTTP-Server und JSON für die Antworten (§11).
    implementation(libs.javalin)
    implementation(libs.jackson.databind)
}

// Wie bei den Bridges entsteht ein einzelnes, ablegbares Jar. Die Distribution
// legt es als lib/module-rest.jar ab; der ServiceLoader findet es dort (§16, §11).
tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

// `jar` und `shadowJar` schreiben über archiveClassifier = "" unter demselben Namen
// nach build/libs. Liefen beide, überschriebe die dünne Klassen-Jar die Shadow-Jar
// — je nach Reihenfolge landete ein unvollständiges Modul-Jar in lib/ (siehe bridge-paper).
tasks.jar {
    enabled = false
}
