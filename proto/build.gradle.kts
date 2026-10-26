plugins {
    alias(libs.plugins.protobuf)
}

dependencies {
    api(libs.protobuf.java)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
}
