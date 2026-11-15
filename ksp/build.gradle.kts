plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "live.arkitekt"
version = rootProject.version
description = "The KSP processor of arkitekt-kotlin: @Action functions become actions"

repositories {
    mavenCentral()
}

dependencies {
    // Only KSP's API: the code is written as text, and the runtime is not on this classpath.
    implementation("com.google.devtools.ksp:symbol-processing-api:2.1.0-1.0.29")
}

kotlin {
    jvmToolchain(17)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
