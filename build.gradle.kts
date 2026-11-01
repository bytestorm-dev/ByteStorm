plugins {
    java
}

group = "dev.bytestorm"

// Die Projektversion steht in gradle.properties: Sie steuert Artefakt-Namen und
// den Beta-/Public-Schalter der GitHub-Action (.github/workflows/release.yml).
version = providers.gradleProperty("version").get()

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
}

tasks.named("build") {
    // Ein `./gradlew build` baut das gesamte Cloud-Projekt, nicht nur das Root.
    dependsOn(subprojects.map { "${it.path}:build" })
}
