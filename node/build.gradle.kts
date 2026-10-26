dependencies {
    implementation(project(":core"))

    // DockerRuntime spricht mit dem Docker-Daemon (§12.3).
    implementation(libs.docker.java)

    // Integrationstest für Handshake/Heartbeat mit einer echten Bridge.
    testImplementation(project(":bridge:bridge-common"))
}
