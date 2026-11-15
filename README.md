# arkitekt-kotlin

[Arkitekt](https://arkitekt.live) for Kotlin and the JVM. It follows the design of the Python
libraries and of [arkirust](https://github.com/arkitektio/arkirust): an app is a declaration, a
runtime logs it in and serves it, and plain functions become actions that anyone in your
Arkitekt deployment can run.

```kotlin
import live.arkitekt.App
import live.arkitekt.Runtime
import live.arkitekt.rekuest.graphql.type.PortKind
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

// An App is a declaration: nothing is contacted here.
val app = App("greeter", "0.1.0")
        .action(
                "greet",
                name = "Greet",
                description = "Say hello to someone.",
                ports = {
                    arg("name", PortKind.STRING, "Who to greet")
                    returns("greeting", PortKind.STRING, "What was said")
                }
        ) { _, args ->
            mapOf("greeting" to JsonPrimitive("Hello ${args["name"]!!.jsonPrimitive.content}"))
        }

suspend fun main() {
    // `connect` authorizes the app (a browser page, once), builds its clients and registers
    // its actions; `serve` runs what the deployment assigns, until it is cancelled.
    val connection = Runtime(app).connect("https://go.arkitekt.live")
    connection.serve()
}
```

## What is in it

| | |
| --- | --- |
| `App`, `Ports`, `Action` | What an app is: its identifier and version, the services it needs, its actions and their ports |
| `Service`, `Context` | A service an app asks for and how its client is built; a handler gets clients with `context[service]` |
| `Runtime`, `Connection` | Logs an app in, builds its `Context`, registers and serves its actions |
| `FaktsClient`, `TokenManager`, `FaktsCache` | The login: discovery, the OAuth2 device-code grant, token rotation, the kept login |
| `Agent`, `Journal`, `AgentMessage`/`AgentEvent` | The agent socket (`/agi`), agent protocol 2: numbered and acknowledged in both directions |
| `Unlok`, `Rekuest`, `graphqlClient` | The two clients every app has, and the authenticated Apollo client others are built on |

## Services

A service is declared once, beside its client, and an app asks for it:

```kotlin
val mikro = Service("mikro", "live.arkitekt.mikro") { Mikro(it.alias, it.tokens) }

val app = App("my-app", "0.1.0").service(mikro)
// in a handler: context[mikro]
```

Services are built in the order they are declared, so one can build on another (`it[mikro]`).
Rekuest is required by every app and needs no declaration.

## Build

```sh
./gradlew build     # compiles, generates the GraphQL clients, runs the tests
```

Built with JDK 17 and compiled to Java 8 bytecode, so hosts such as Fiji can load it. The tests
need no server: the agent's lifecycle runs against a local stand-in for the gateway.

It is not published to a Maven repository yet. Use it as a Gradle composite build:

```kotlin
// settings.gradle.kts
includeBuild("../arkitekt-kotlin")
// build.gradle.kts
dependencies { implementation("live.arkitekt:arkitekt-kotlin") }
```

## Not here yet

States, locks, child calls and workflows; registering over the socket (`REGISTER` carries no
declaration, actions are registered with `implementAgent`); a journal that survives a restart.
