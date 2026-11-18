import java.net.URI
import java.security.MessageDigest

// arkitekt-meshd, the mesh sidecar, for every platform it is released for: resources only.
// The library (live.arkitekt.Meshd) finds the binary for the machine it runs on under
// live/arkitekt/meshd/<os>-<arch>/ and runs it. An app that wants the mesh without a binary of
// its own on the machine depends on this as well as on the library.
plugins {
    `java-library`
    `maven-publish`
}

group = "live.arkitekt"
version = rootProject.version
description = "arkitekt-meshd, the Arkitekt mesh sidecar, for every platform, for arkitekt-kotlin"

// The release of arkitektio/arkitekt-mesh to bundle. Raise it together with meshd.sha256: the
// hashes there are what each download is checked against, so a release asset that changes after
// it was pinned (or a download that is not what it says) fails the build.
val meshdVersion = "0.15.1"

val platforms = listOf("linux-amd64", "linux-arm64", "darwin-amd64", "darwin-arm64", "windows-amd64", "windows-arm64")

fun exe(platform: String) = if (platform.startsWith("windows")) "arkitekt-meshd.exe" else "arkitekt-meshd"

val pinned: Map<String, String> =
        file("meshd.sha256").readLines().filter { it.isNotBlank() }.associate { line ->
            val (sha, name) = line.trim().split(Regex("\\s+"), limit = 2)
            name to sha
        }

val generated = layout.buildDirectory.dir("generated/meshd")

val downloadMeshd by tasks.registering {
    group = "build"
    description = "Download arkitekt-meshd $meshdVersion for every platform and check it against meshd.sha256"
    inputs.property("version", meshdVersion)
    inputs.file("meshd.sha256")
    outputs.dir(generated)

    doLast {
        val root = generated.get().asFile.resolve("live/arkitekt/meshd")
        root.deleteRecursively()
        root.mkdirs()
        val base = "https://github.com/arkitektio/arkitekt-mesh/releases/download/v$meshdVersion"

        fun fetch(asset: String, to: File) {
            val expected = pinned[asset] ?: error("meshd.sha256 pins no hash for $asset")
            to.parentFile.mkdirs()
            URI("$base/$asset").toURL().openStream().use { input -> to.outputStream().use { input.copyTo(it) } }
            val actual = MessageDigest.getInstance("SHA-256").digest(to.readBytes()).joinToString("") { "%02x".format(it) }
            check(actual == expected) {
                to.delete()
                "$asset from $base has sha256 $actual, but meshd.sha256 pins $expected"
            }
        }

        val sums = StringBuilder()
        for (platform in platforms) {
            val suffix = if (platform.startsWith("windows")) ".exe" else ""
            val asset = "arkitekt-meshd-$meshdVersion-$platform$suffix"
            fetch(asset, root.resolve("$platform/${exe(platform)}"))
            sums.append("${pinned.getValue(asset)}  $platform/${exe(platform)}\n")
        }
        fetch("THIRD_PARTY_LICENSES", root.resolve("THIRD_PARTY_LICENSES"))

        // What the library reads: which version this is, and what each binary must hash to
        // (checked again on every start, against the copy it runs).
        root.resolve("VERSION").writeText(meshdVersion + "\n")
        root.resolve("SHA256SUMS").writeText(sums.toString())
    }
}

sourceSets.main {
    resources.srcDir(downloadMeshd)
}

java {
    // No classes: it declares Java 8, like the library, so Fiji's Java 8 builds may take it.
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
