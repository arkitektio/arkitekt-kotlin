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
- A **`Task`** parameter is the running task: `task.progress(50, "half way")` and
  `task.log("…")` tell whoever watches how far it is. A handler written by hand asks for
  `Task.current()`.

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

## States

An app can hold state: what a piece of hardware, or a host such as Fiji, is doing right now.
`@State` on a data class makes it one, as `@app.state` does in Python and `#[derive(State)]` in
Rust. The processor writes `<Class>State` beside it.

```kotlin
/**
 * Where the stage is.
 *
 * @property x Microns from the origin, along x
 */
@State(locks = ["stage"])
data class StagePosition(val x: Double = 0.0, val y: Double = 0.0)

@Action
suspend fun moveTo(x: Double, y: Double, stage: StateMut<StagePosition>, task: Task): Double {
    task.progress(50, "moving")
    stage.update { it.copy(x = x, y = y) }
    return x
}

val app = App("stage", "0.1.0")
    .state(StagePositionState, StagePosition())
    .action(MoveToAction)
```

- **Fields** are the properties of the primary constructor: `String`, `Int`/`Long`,
  `Float`/`Double`, `Boolean`, and a `List` of those. A nullable type is a nullable field. A
  field is described with `@property` in the class's KDoc, or by KDoc on the property.
- **Changes are published.** `update` takes the value and returns the next one. What differs
  goes out as JSON Patch operations (one `STATE_PATCH` each), so whoever watches the app knows
  its state. Do not write actions that only read a state back; reading is what watching is for.
  A `StateRef<T>` parameter is for an action that needs to consult a state while doing its work.
- **Locks.** `locks` on a state means a task must hold them to change it. An action that takes
  the state as a `StateMut` holds them without saying so, and `@Action(locks = ["motor"])`
  names further ones. Two tasks that need the same lock run one after the other.
- **Outside a task**, `connection.state(StagePositionState)` (or `context.state(…)` in a
  provider) gives a handle a listener can write to. It can only change a state without locks.

## Bloks

An app can ship its own UI: a blok, written in BSX, that reads the app's states and calls its
actions.

```kotlin
const val STAGE_PANEL = """<Blok kind="agent" context="panel">
  <Card>
    <Text>{self.StagePosition.x}</Text>
    <Button onClick={self.move_to(x: 0, y: 0)}>Home</Button>
  </Card>
</Blok>"""

val app = App("stage", "0.1.0")
    .state(StagePositionState, StagePosition())
    .action(MoveToAction)
    .blok("panel", STAGE_PANEL, description = "Where the stage is")
```

The source is all the app says. The `<Blok>` element names what the blok is (`kind`) and what
draws it (`context`: `panel`, `scene` or `logo`); `self.<State>.<field>` reads a state and
`self.<action>(…)` calls an action, by its key. Nothing in this library parses BSX: the rekuest
server does, and works out from the source which states and actions the blok depends on.

A source that does not parse, or that reads a state or calls an action the app does not declare,
is refused: `Runtime.connect` throws, with the server's finding. `args = { arg("lens",
PortKind.STRUCTURE, identifier = "@mikro/lens") }` declares the items a blok is opened for (read
as `args.<key>`), and `default = true` makes it what a UI opens first. Write the source as a
literal, not as a string built at run time.

## What is in it

| | |
| --- | --- |
| `App`, `Ports` | What an app is: its identifier and version, the services it needs, its actions and their ports |
| `@Action`, `@Inject`, `@Returns`, `Structure` | Actions written as plain functions, and the processor (`arkitekt-kotlin-ksp`) that reads them |
| `@Requires`, `@Provides`, `Descriptors` | What a port requires of a structure and says of one it returns, declared and tested |
| `@State`, `StateMut`, `StateRef` | State an app holds, published as it changes; the locks it is changed under |
| `Task` | The running task, for an action: progress, logs, the app's states |
| `App.blok` | UI the app ships, as its BSX source |
| `Service`, `Context` | A service an app asks for and how its client is built; a handler gets clients with `context[service]` |
| `Runtime`, `Connection` | Logs an app in, builds its `Context`, registers and serves its actions |
| `FaktsClient`, `TokenManager`, `FaktsCache` | The login: discovery, the OAuth2 device-code grant, token rotation, the kept login |
| `Agent`, `Journal`, `AgentMessage`/`AgentEvent` | The agent socket (`/agi`), agent protocol 2: numbered and acknowledged in both directions |
| `Unlok`, `Rekuest`, `graphqlClient` | The two clients every app has, and the authenticated Apollo client others are built on |

## Services

A service is declared once, beside its client, and an app asks for it:

```kotlin
val mikro = Service("mikro", "live.arkitekt.mikro", scopes = listOf("mikro_read", "mikro_write")) {
    Mikro(it.alias, it.tokens)
}

val app = App("my-app", "0.1.0").service(mikro)
// in a handler: context[mikro]
```

Services are built in the order they are declared, so one can build on another (`it[mikro]`).
Rekuest is required by every app and needs no declaration.

### Scopes

The login asks for what the app will do, and gets a token that may do exactly that. A service
says what its client needs (`scopes`): the scopes the server's schema asks for
(`@auth(required_scopes: […])`) on every root field the client's operations select. An app that
declares the service asks for them, beside `openid` and `rekuest_agent`, which every app needs
to register and be assigned work. `App(…).scope("…")` asks for more; an app that asks lok who is
logged in (`context.unlok.getUser()`) adds `.scopes(Unlok.SCOPES)`.

A scope that is not asked for is not in the token, and the operation that needs it is refused
at run time. Hold the list to the schema with a test, as `ScopesTest` does for this library's
own operations (`requiredScopes(folder)` reads a folder of `*.graphqls` and `*.graphql`). A
login that was kept from before a scope was added has to be made again.

## Build

```sh
./gradlew build     # compiles, generates the GraphQL clients, runs the tests
```

Built with JDK 17 and compiled to Java 8 bytecode, so hosts such as Fiji can load it. The tests
need no server: the agent's lifecycle runs against a local stand-in for the gateway.

```sh
./gradlew integrationTest   # needs Docker
```

starts a real rekuest and takt (`src/integrationTest/stack`, a copy of rekuest's own test stack)
and runs an agent against them: an assigned task comes back with its result, and a task whose
connection is dropped half way still runs once and is reported once.

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

## Releases

A push to `main` that carries a `feat` or a `fix` ([Conventional Commits](https://www.conventionalcommits.org))
is a release once the build and the integration test are green: semantic-release tags the next
`vX.Y.Z` and writes the GitHub release. The tag is the version; nothing in the repository
carries it. Before 1.0 a breaking change raises the minor.

## Not here yet

Child calls and workflows; several returns, enums, dictionaries and dates as ports; registering
over the socket (`REGISTER` carries no declaration: actions, states, locks and bloks are
registered with `implementAgent`); a journal that survives a restart.

Of states: a state that is kept across restarts, structures as fields, hooks that run at startup
or in the background, and requirements on a state when a task is assigned. Of bloks: an action's
own bloks (its logo, what it asks a person) and the files a blok names (`self.assets`).

A blok that names a state or an action needs rekuest-server 21.4 or newer, which works out a
blok's dependencies from its source. An older server registers the blok without them.
