dependencies {
    // Die öffentliche API exponiert Proto-Typen (ServiceInfo, ServiceGroup, Events).
    api(project(":proto"))

    // ModuleContext reicht einen Logger an Module weiter.
    api(libs.slf4j.api)
}
