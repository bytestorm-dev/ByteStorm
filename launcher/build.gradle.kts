import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.io.File
import java.security.MessageDigest

/**
 * Leitet den Classifier aus dem Dateinamen ab: Maven legt klassifizierte
 * Artefakte als {@code <artifact>-<version>-<classifier>.jar} ab.
 */
fun classifierOf(fileName: String, artifactId: String, version: String): String {
    val plain = "$artifactId-$version.jar"
    if (fileName == plain) {
        return ""
    }
    val prefix = "$artifactId-$version-"
    return if (fileName.startsWith(prefix) && fileName.endsWith(".jar")) {
        fileName.removePrefix(prefix).removeSuffix(".jar")
    } else {
        ""
    }
}

/** @return SHA-256 als Hex */
fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

val externalJars = provider {
    configurations.runtimeClasspath.get().incoming.artifactView {
        componentFilter { it is ModuleComponentIdentifier }
    }.artifacts.artifacts.map { it.file }.toSet()
}

// Liste der Fremd-Jars, die der Launcher beim Start nachlädt (§5.1). Format:
// groupId:artifactId:version:classifier:sha256 — der Classifier darf leer sein.
val dependencyList = tasks.register("dependencyList") {
    group = "distribution"
    description = "Schreibt die nachzuladenden Fremd-Jars mit SHA-256 nach lib/dependencies.txt."

    val runtime = configurations.runtimeClasspath
    inputs.files(runtime)
    outputs.file(layout.buildDirectory.file("distribution/dependencies.txt"))

    doLast {
        val artifacts = runtime.get().incoming.artifactView {
            componentFilter { it is ModuleComponentIdentifier }
        }.artifacts.artifacts
        val lines = artifacts.mapNotNull { artifact ->
            val module = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: return@mapNotNull null
            val classifier = classifierOf(artifact.file.name, module.module, module.version)
            "${module.group}:${module.module}:${module.version}:$classifier:${sha256(artifact.file)}"
        }.distinct().sorted()

        val file = layout.buildDirectory.file("distribution/dependencies.txt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"))
    }
}

dependencies {
    // Bootstrap ist der Composition Root: Config, Provider, Healthcheck, Shutdown.
    implementation(project(":core"))

    // lib/.manifest.json und cloud.json (de)serialisieren.
    implementation(libs.jackson.databind)

    // Erststart-Wizard liest interaktiv vom Terminal (§5.1, §17).
    implementation(libs.jline)

    // ConsoleLogBridge hängt einen eigenen Appender an den Logger, damit
    // Konsolen-Logs nicht in die Eingabezeile schreiben.
    implementation(libs.logback.classic)

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

// Fertige Cloud-Distribution: das Launcher-Jar, die ByteStorm-Module und die
// Liste der Fremd-Jars. Die Fremd-Jars selbst lädt der Launcher beim ersten
// Start von Maven Central nach (§5.1) — das Zip bleibt dadurch klein.
// Die Bridge-Plugins liegen als lib/bridge-<platform>.jar bereit, weil der
// ServiceProvisioner sie genau dort sucht (§12.2). Wer das Zip in ein
// Cloud-Verzeichnis entpackt, kann die Cloud starten.
val bridgePlatforms = listOf("bungeecord", "paper", "velocity")

// Optionale Module liegen als lib/module-<name>.jar bereit: Dort findet sie der
// ServiceLoader (§16), und ihr Schaltzustand in MongoDB entscheidet, ob sie laufen.
val modules = listOf("discord", "rest")

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
    // Nur die ByteStorm-Jars bleiben im Zip; Fremd-Jars kommen über den Launcher.
    from(provider { configurations.runtimeClasspath.get().filterNot { it in externalJars.get() } }) {
        into("lib")
    }
    from(dependencyList) {
        into("lib")
    }
    dependsOn(configurations.runtimeClasspath, dependencyList)
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
    // Proxy-Plugins liegen unter plugins/ im Zip: Der Betreiber kopiert sie in den
    // plugins/-Ordner seines Proxys; sie laufen nicht in der Cloud.
    from(project(":plugins:proxy-plugin").tasks.named("shadowJar")) {
        into("plugins")
        rename { "bytestorm-proxy.jar" }
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

