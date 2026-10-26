dependencies {
    // Gemeinsame Bridge-Logik: api inkl. Proto-Typen, Codec ohne Mongo/Jackson.
    api(project(":api"))
    api(project(":codec"))

    // Wird von der jeweiligen Plattform bereitgestellt, nicht geshadet.
    compileOnly(libs.slf4j.api)

    // Nur für Tests: eine echte SLF4J-Implementierung, damit Warnungen sichtbar sind.
    testRuntimeOnly(libs.logback.classic)
}
