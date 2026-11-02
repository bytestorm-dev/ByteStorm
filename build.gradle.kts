// Für `configure<PublishingExtension>` und `create<MavenPublication>` unten.
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
// Nicht als `java.util.Properties` schreiben: `java` ist hier das Java-Plugin.
import java.util.Properties

plugins {
    java
}

group = "dev.bytestorm"

// Die Projektversion steht in gradle.properties: Sie steuert Artefakt-Namen und
// den Beta-/Public-Schalter der GitHub-Action (.github/workflows/release.yml).
version = providers.gradleProperty("version").get()

// Zugang zum eigenen Maven-Repository. Die Datei steht in .gitignore: Wer keine
// hat, baut und testet normal weiter, nur `publish` fehlt dann.
val repositoryCredentials = Properties().apply {
    val file = file("repository.credentials")
    if (file.isFile) file.inputStream().use { load(it) }
}

// Der generierte `libs`-Accessor gilt nur für dieses Skript, nicht für `subprojects {}`.
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = catalog.findLibrary(alias).orElseThrow {
    IllegalStateException("Library '$alias' fehlt in gradle/libs.versions.toml")
}.get()

// Gemeinsame Konventionen für alle Subprojekte (Konzept §3, §22).
// Die Modul-Builds deklarieren nur noch ihre eigenen Abhängigkeiten.
subprojects {
    // java-library, damit `api` (Transitiv-Abhängigkeiten) verfügbar ist.
    apply(plugin = "java-library")

    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
        // Plattform-APIs für die Bridge-Plugins
        maven("https://repo.papermc.io/repository/maven-public/")
        // PlaceholderAPI für die Platzhalter-Erweiterung
        maven("https://repo.extendedclip.com/releases")
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(catalog.findVersion("jvm").get().requiredVersion.toInt()))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation"))
    }

    dependencies {
        add("compileOnly", lib("lombok"))
        add("annotationProcessor", lib("lombok"))

        add("testImplementation", platform(lib("junit-bom")))
        add("testImplementation", lib("junit-jupiter"))
        add("testRuntimeOnly", lib("junit-platform-launcher"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    // Jedes Modul mit eigenem Code lässt sich veröffentlichen (§22). Die
    // Sammelprojekte bridge/, modules/ und plugins/ tragen nur Unterprojekte und
    // erzeugen deshalb kein leeres Artefakt; proto zählt über seine .proto-Dateien.
    if (path !in setOf(":bridge", ":modules", ":plugins")
            && (file("src/main/java").isDirectory || file("src/main/proto").isDirectory)) {
        apply(plugin = "maven-publish")
        // Erst nach dem eigenen Build-Skript: Ob ein Modul sein dünnes Jar abschaltet
        // und stattdessen die Shadow-Jar veröffentlicht, steht erst dort.
        afterEvaluate {
        configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    // Shadow-Module schalten das dünne Jar ab (gleicher Dateiname wie
                    // die fette Jar). Dort wird die Shadow-Jar veröffentlicht, sonst
                    // fände niemand die mitgelieferten Abhängigkeiten.
                    if (tasks.named("jar").get().enabled) {
                        from(components["java"])
                    } else {
                        artifact(tasks.named("shadowJar"))
                    }
                    pom {
                        name.set(project.name)
                        description.set("ByteStorm — ${project.name}")
                    }
                }
            }
            repositories {
                // Ohne Zugangsdatei bleibt der Block leer: `publish` gibt es dann
                // nicht, statt beim Ausführen an fehlenden Werten zu scheitern.
                if (repositoryCredentials.getProperty("repo.url") != null) {
                    maven {
                        name = "bytestorm"
                        val repositoryName = repositoryCredentials
                                .getProperty("repo.repository", "releases")
                        url = uri(repositoryCredentials.getProperty("repo.url").trimEnd('/')
                                + "/" +                    repositoryName + "/")
                        // Das Repository spricht noch HTTP; Gradle blockt das sonst.
                        isAllowInsecureProtocol = true
                        credentials {
                            username = repositoryCredentials.getProperty("repo.username")
                            password = repositoryCredentials.getProperty("repo.password")
                        }
                    }
                }
            }
        }
        }
    }
}

tasks.named("build") {
    // Ein `./gradlew build` baut das gesamte Cloud-Projekt, nicht nur das Root.
    dependsOn(subprojects.map { "${it.path}:build" })
}
