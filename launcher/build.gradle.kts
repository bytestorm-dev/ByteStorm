dependencies {
    // Bootstrap ist der Composition Root: Config, Provider, Healthcheck, Shutdown.
    implementation(project(":core"))

    // lib/.manifest.json und cloud.json (de)serialisieren.
    implementation(libs.jackson.databind)

    // Erststart-Wizard liest interaktiv vom Terminal (§5.1, §17).
    implementation(libs.jline)

    // Single-Node: Controller, Node und CLI laufen im selben Prozess (§2).
    // Logback kommt transitiv über core.
    implementation(project(":controller"))
    implementation(project(":node"))
    implementation(project(":cli"))
}

// Der Launcher ist das Einstiegs-Jar der Distribution; erst mit Main-Class lässt
// er sich als `java -jar bytestorm.jar` starten (§5.1).
tasks.jar {
    manifest {
        attributes["Main-Class"] = "dev.bytestorm.launcher.Launcher"
    }
}

// Fertige Cloud-Distribution: das Launcher-Jar plus alle Laufzeit-Jars in lib/.
// Die Bridge-Plugins liegen als lib/bridge-<platform>.jar bereit, weil der
// ServiceProvisioner sie genau dort sucht (§12.2). Wer das Zip in ein
// Cloud-Verzeichnis entpackt, kann die Cloud direkt starten.
val bridgePlatforms = listOf("bungeecord", "paper", "velocity")

// Optionale Module liegen als lib/module-<name>.jar bereit: Dort findet sie der
// ServiceLoader (§16), und ihr Schaltzustand in MongoDB entscheidet, ob sie laufen.
val modules = listOf("rest")

tasks.register<Zip>("distributionZip") {
    group = "distribution"
    description = "Packt die startbare Cloud (bytestorm.jar + lib/) als Zip."

    archiveFileName.set("bytestorm-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(tasks.named("jar")) {
        rename { "bytestorm.jar" }
    }
    // Der Bootstrap liegt in diesem Jar und wird laut §5.1 aus lib/ geladen; dasselbe
    // Jar muss deshalb auch in lib/ liegen. Läge es nur als Einstiegs-Jar daneben,
    // definierte der App-Loader den Bootstrap und dessen Abhängigkeiten aus lib/
    // wären ihm unbekannt (NoClassDefFoundError beim Start).
    from(tasks.named("jar")) {
        into("lib")
        rename { "launcher.jar" }
    }
    from(configurations.runtimeClasspath) {
        into("lib")
    }
    bridgePlatforms.forEach { platform ->
        from(project(":bridge:bridge-$platform").tasks.named("shadowJar")) {
            into("lib")
            rename { "bridge-$platform.jar" }
        }
    }
    modules.forEach { module ->
        from(project(":modules:module-$module").tasks.named("shadowJar")) {
            into("lib")
            rename { "module-$module.jar" }
        }
    }
}

// Der CloudStartupE2ETest kopiert die fertigen Bridge-Jars aus den Bridge-Modulen.
// Ohne diese Abhängigkeit dürfte er dank org.gradle.parallel=true parallel zum
// Shadow-Jar laufen und eine halb geschriebene Plugin-Jar erwischen.
tasks.test {
    dependsOn(
        ":bridge:bridge-velocity:shadowJar",
        ":bridge:bridge-paper:shadowJar",
        ":bridge:bridge-bungeecord:shadowJar")

    // Der CloudStartupE2ETest nennt diesen Schalter in seiner Meldung: mit
    // -Dbytestorm.keepTestDirs=true bleibt der Cloud-Ordner nach dem Lauf stehen.
    System.getProperty("bytestorm.keepTestDirs")?.let {
        systemProperty("junit.jupiter.tempdir.cleanup.mode.default", "NEVER")
    }
}

