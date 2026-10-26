dependencies {
    // Wire-Format und Type-URLs; keine Persistenz, damit Bridges den Codec
    // ohne MongoDB/Jackson nutzen können (§6.2, §15).
    api(project(":proto"))
    api(libs.redisson)
}
