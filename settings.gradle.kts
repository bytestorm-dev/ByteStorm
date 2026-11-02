pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Provisioniert bei Bedarf ein JDK 26 für die Toolchain (siehe gradle/libs.versions.toml)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ByteStorm"

include("proto")
include("api")
include("codec")
include("core")
include("launcher")
include("controller")
include("node")
include("cli")
include("bridge")
include("bridge:bridge-common")
include("bridge:bridge-paper")
include("bridge:bridge-velocity")
include("bridge:bridge-bungeecord")
include("modules")
include("modules:module-rest")
include("modules:module-discord")
include("plugins")
include("plugins:proxy-plugin")
include("plugins:lobby-plugin")
