plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("com.apollographql.apollo") version "4.0.0"
    // Only for this project's own tests, which declare @Action functions.
    id("com.google.devtools.ksp") version "2.1.0-1.0.29"
    `java-library`
    `maven-publish`
}

group = "live.arkitekt"
// A release's version comes from its tag (`-PlibraryVersion=`); a local build is a snapshot.
version = (findProperty("libraryVersion") as String?) ?: "0.1.0-SNAPSHOT"
description = "Arkitekt for Kotlin: declare an app, log it in, serve its actions"

repositories {
    mavenCentral()
}

dependencies {
    // In the public API: an app's ports are the generated rekuest types, clients are Apollo
    // clients, handlers take JSON elements and are suspend functions.
    api("com.apollographql.apollo:apollo-runtime:4.0.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0-RC")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // fakts speaks plain HTTP (OkHttp); the agent socket is Ktor's.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.ktor:ktor-client-core:3.0.2")
    implementation("io.ktor:ktor-client-cio:3.0.2")
    implementation("io.ktor:ktor-client-websockets:3.0.2")

    // The processor, run over the test sources: its output is what DeclaredActionTest checks.
    kspTest(project(":arkitekt-kotlin-ksp"))

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.ktor:ktor-server-core:3.0.2")
    testImplementation("io.ktor:ktor-server-netty:3.0.2")
    testImplementation("io.ktor:ktor-server-websockets:3.0.2")
}

tasks.test {
    useJUnitPlatform()
}

// Tests against a real rekuest and takt, started in Docker (src/integrationTest/stack). Their own
// source set and task, so `test` and `build` stay server-free: `./gradlew integrationTest`.
val integrationTest: SourceSet by sourceSets.creating {
    // The library, and the unit tests' helpers (a login that needs no server).
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    // The integration test's actions are @Action functions, run through the processor.
    add("kspIntegrationTest", project(":arkitekt-kotlin-ksp"))
}

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs an agent against a real rekuest and takt (needs Docker)"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    // Where the stack's compose file is, and never a stale "up to date": the server is the input.
    systemProperty("arkitekt.stack", file("src/integrationTest/stack").absolutePath)
    outputs.upToDateWhen { false }
    testLogging { events("passed", "failed", "skipped") }
}

// Built with a JDK 17 toolchain, compiled to Java 8 bytecode: hosts such as Fiji still run it.
kotlin {
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
}

apollo {
    service("lok") {
        packageName.set("live.arkitekt.lok.graphql")
        srcDir("src/main/graphql/lok")
    }
    service("rekuest") {
        packageName.set("live.arkitekt.rekuest.graphql")
        srcDir("src/main/graphql/rekuest")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
