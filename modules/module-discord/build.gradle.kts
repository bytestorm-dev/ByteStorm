plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    // §18: Das Modul spricht ausschließlich über die öffentlichen api-Interfaces;
    // Redisson und Mongo kennt es nicht. Der Webhook läuft über java.net.http aus
    // dem JDK, ein Bot-Token und JDA sind dafür nicht nötig.
    implementation(project(":api"))
}

// Wie bei REST und den Bridges entsteht ein einzelnes, ablegbares Jar: Die
// Distribution legt es als lib/module-discord.jar ab, der ServiceLoader findet es
// dort (§16).
tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

// `jar` und `shadowJar` schrieben sonst unter demselben Namen nach build/libs —
// je nach Reihenfolge überschriebe die dünne Klassen-Jar die Shadow-Jar (siehe
// bridge-paper).
tasks.jar {
    enabled = false
}
