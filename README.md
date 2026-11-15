# arkitekt-kotlin

[Arkitekt](https://arkitekt.live) for Kotlin and the JVM. It follows the design of the Python
libraries and of [arkirust](https://github.com/arkitektio/arkirust): an app is a declaration, a
runtime logs it in and serves it, and plain functions become actions that anyone in your
Arkitekt deployment can run.

```kotlin
import live.arkitekt.Action
import live.arkitekt.App
import live.arkitekt.Runtime

/**
 * Greet
 *
 * Say hello to someone.
 *
 * @param name Who to greet
 * @param times How often
 * @return What was said
 */
@Action
fun greet(name: String, times: Int = 1): String = "Hello $name! ".repeat(times).trim()

suspend fun main() {
    // An App is a declaration: nothing is contacted here. `GreetAction` is written by the
    // processor from the function above.
    val app = App("greeter", "0.1.0").action(GreetAction)

    // `connect` authorizes the app (a browser page, once), builds its clients and registers
    // its actions; `serve` runs what the deployment assigns, until it is cancelled.
    val connection = Runtime(app).connect("https://go.arkitekt.live")
    connection.serve()
}
```

## Actions from functions

`@Action` on a top-level function makes it an action, as `@register` does in Python and
`#[action]` in Rust. A [KSP](https://kotlinlang.org/docs/ksp-overview.html) processor reads the
function when it is compiled and writes `<FunctionName>Action` beside it: the definition, and
the handler that turns the arguments off the wire into the parameters and the result back.
Nothing is looked up by reflection at run time.

- **Ports** come from the signature: `String`, `Int`/`Long`, `Float`/`Double`, `Boolean`, a
  `List` of those, and any class whose companion object is a `Structure`. A nullable type is a
  nullable port. So is a parameter with a default value: the default applies when nothing is
  sent (at most four such parameters).
- **Words** come from the KDoc. With more than one paragraph the first is the action's name and
  the rest its description; a single paragraph is the description, and the name is the
  function's (`showLens` → "Show Lens"). `@param` describes a port, `@return` the result.
- **`@Action(key, name, description)`** overrides what is derived; the key is the function's
  name in snake case otherwise. **`@Returns("key")`** names the return (`return0` otherwise).
- **`@Inject`** marks a parameter the app fills instead of the caller, found by its type: the
  `Context`, a declared service's client, or what the app provides with `App.provide(…)`.

A structure travels by id, under an identifier:

```kotlin
class Lens(val id: String) {
    companion object : IdStructure<Lens>("@mikro/lens", ::Lens, Lens::id)
}

@Action
suspend fun showLens(lens: Lens, @Inject viewer: Viewer): Lens { … }
```

### Requires and provides

A structure has descriptors: facts about one object under a key a service declares
(`@mikro/n_space_axes` = 3). A port states constraints over them. On an argument they are what
it requires (a picker offers only objects that fit, and the server matches on them); on a
return, what the action provides.

As in Python's `Annotated[Lens, Requires(…), Provides(…)]`, a spec is a type alias that carries
both, and each port keeps its own side:

```kotlin
@Requires(N_SPACE_AXES, DescriptorOperator.EQUALS, "3")
@Provides(N_SPACE_AXES, DescriptorOperator.EQUALS, "3")
typealias Volume = Lens

@Action
suspend fun deconvolve(image: Volume): Volume { … }
```

A constraint can also be written where it is used: `@Requires` on a parameter, `@Provides` on
the function. Both repeat. The value is JSON text, since an annotation cannot hold any value:
`"3"`, `"\"tif\""`, `"[\"tif\", \"tiff\"]"`.

A structure that implements `describe` is also tested when it crosses a constrained port: an
argument after it is expanded, a return before it is shrunk. One that does not fit fails the
task, naming the constraint and the actual value. A key `describe` does not return is not
tested, and `describe` is only called for a port that states a constraint.

`describe` reads what the value already holds; it cannot ask a server (it is not suspending
and gets no context). Fetch the descriptors with the object, in the query `expand` makes: a
second round trip to describe an object is an antipattern, and doubles every constrained call.

A function the processor cannot make an action of fails the build, at the function, saying why.
An action can still be declared by hand with `App.action(key, name, …) { context, args -> … }`.

## What is in it

| | |
| --- | --- |
| `App`, `Ports` | What an app is: its identifier and version, the services it needs, its actions and their ports |
| `@Action`, `@Inject`, `@Returns`, `Structure` | Actions written as plain functions, and the processor (`arkitekt-kotlin-ksp`) that reads them |
| `@Requires`, `@Provides`, `Descriptors` | What a port requires of a structure and says of one it returns, declared and tested |
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
plugins { id("com.google.devtools.ksp") version "2.1.0-1.0.29" }   // for @Action
dependencies {
    implementation("live.arkitekt:arkitekt-kotlin")
    ksp("live.arkitekt:arkitekt-kotlin-ksp")
}
```

## Not here yet

States, locks, child calls and workflows; several returns, enums, dictionaries and dates as
ports, and reporting progress from inside an action; registering over the socket (`REGISTER` carries no
declaration, actions are registered with `implementAgent`); a journal that survives a restart.
