dependencies {
    implementation(project(":core"))
    // ServiceController exponiert ServiceDefinition und Lifecycle-Ausnahmen.
    api(project(":node"))
}
