package live.arkitekt

import com.apollographql.apollo.api.Optional
import kotlinx.serialization.json.JsonElement
import live.arkitekt.rekuest.graphql.type.ActionKind
import live.arkitekt.rekuest.graphql.type.ArgPortInput
import live.arkitekt.rekuest.graphql.type.BlokImplementationInput
import live.arkitekt.rekuest.graphql.type.DefinitionInput
import live.arkitekt.rekuest.graphql.type.ImplementAgentInput
import live.arkitekt.rekuest.graphql.type.ImplementationInput
import live.arkitekt.rekuest.graphql.type.LockDefinitionInput
import live.arkitekt.rekuest.graphql.type.LockImplementationInput
import live.arkitekt.rekuest.graphql.type.PortKind
import live.arkitekt.rekuest.graphql.type.ReturnPortInput
import live.arkitekt.rekuest.graphql.type.StateDefinitionInput
import live.arkitekt.rekuest.graphql.type.StateImplementationInput

// The app pattern, as in arkitekt (Python) and arkirust: an App is a declaration, and a Runtime
// runs it.
//
//     val app = App("my-app", "0.1.0")
//             .service(mikro)
//             .action("greet", name = "Greet", ports = {
//                 arg("name", PortKind.STRING, "Who to greet")
//                 returns("greeting", PortKind.STRING)
//             }) { context, args -> … }
//
//     val connection = Runtime(app).connect("https://go.arkitekt.live")
//     connection.serve()
//
// Declaring does nothing: no login, no socket, no client. That makes an App something a test can
// build and an agent can be handed without a server.

/** What an action is run with: the app's [Context], and its arguments by port key. */
typealias Handler = suspend (Context, Map<String, JsonElement?>) -> Map<String, JsonElement?>

/**
 * One action an app offers: what it takes and returns, and the function that does it. A task
 * holds its [locks] while it runs; [manipulates] names the states it changes.
 */
class RegisteredAction(
        val definition: DefinitionInput,
        val handler: Handler,
        val locks: List<String> = emptyList(),
        val manipulates: List<String> = emptyList()
) {
    val key: String get() = definition.key
}

/** The blok language version a source is written in. */
const val BLOK_VOCAB = 2

/**
 * A blok an app declares: UI it ships with its actions and states, as its BSX source. See
 * [App.blok].
 */
class DeclaredBlok(
        val key: String,
        val source: String,
        val description: String? = null,
        val default: Boolean = false,
        val forModel: String? = null,
        val forNode: String? = null,
        val args: List<ArgPortInput> = emptyList()
)

/** The ports of an action, in the order they are declared. */
class Ports {
    internal val args = mutableListOf<ArgPortInput>()
    internal val returns = mutableListOf<ReturnPortInput>()

    /** An argument. [identifier] names the structure of a `STRUCTURE` port (`@mikro/lens`). */
    fun arg(
            key: String,
            kind: PortKind,
            description: String? = null,
            identifier: String? = null,
            nullable: Boolean? = false
    ) {
        args +=
                ArgPortInput(
                        key = key,
                        kind = kind,
                        identifier = Optional.presentIfNotNull(identifier),
                        description = Optional.presentIfNotNull(description),
                        nullable = Optional.presentIfNotNull(nullable)
                )
    }

    /** A return value. The handler's map must carry it under [key]. */
    fun returns(
            key: String,
            kind: PortKind,
            description: String? = null,
            identifier: String? = null,
            nullable: Boolean? = false
    ) {
        returns +=
                ReturnPortInput(
                        key = key,
                        kind = kind,
                        identifier = Optional.presentIfNotNull(identifier),
                        description = Optional.presentIfNotNull(description),
                        nullable = Optional.presentIfNotNull(nullable)
                )
    }
}

/**
 * A service an app can ask for: where the deployment knows it (`live.arkitekt.mikro`), the key
 * the app's login names it by, and how its client is made once the login says where it lives.
 *
 * Declared once, beside its client, and handed to [App.service]; a handler gets the client back
 * with `context[service]`. Services are built in the order an app declares them, so one may
 * build on another (`scope[other]`).
 */
class Service<T : Any>(
        val key: String,
        val service: String,
        val optional: Boolean = false,
        val build: (ServiceScope) -> T
)

/** What a [Service] is built from: its address, the login's tokens, and the services before it. */
class ServiceScope(
        val alias: Alias,
        val tokens: TokenManager,
        private val built: Map<Service<*>, Any>
) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(service: Service<T>): T =
            built[service] as T?
                    ?: throw IllegalStateException("'${service.key}' is not built yet: declare it earlier")
}

/**
 * What a logged-in app works with: the clients of the services it declared, and the two every
 * app has (lok and rekuest). A [Runtime] builds it at login; every handler is handed it.
 */
class Context(
        val unlok: Unlok,
        val rekuest: Rekuest,
        val tokens: TokenManager,
        private val services: Map<Service<*>, Any> = emptyMap()
) {
    /** What the app provides beside its services ([App.provide]). */
    internal val provided: MutableList<Any> = mutableListOf()

    /** The app's states as they are now. */
    internal val states = States()

    /**
     * One of the app's states, to change from outside a task: a host's listener, a provider. It
     * can only change a state that needs no lock; an action takes a [StateMut] parameter instead.
     */
    fun <T> state(declaration: StateDeclaration<T>): StateMut<T> =
            StateMut(states, states.cell(declaration), null)

    /**
     * Something of type [type]: this context, a declared service's client, or what the app
     * provides. What an `@Inject` parameter of an action is filled with.
     */
    fun <T : Any> inject(type: Class<T>): T {
        val found =
                if (type.isInstance(this)) this
                else (services.values + provided).firstOrNull { type.isInstance(it) }
        return type.cast(
                found
                        ?: throw IllegalStateException(
                                "Nothing of type ${type.name} to inject: declare its service or provide it on the App"
                        )
        )
    }

    /** The client of a service the app declared. */
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(service: Service<T>): T =
            services[service] as T?
                    ?: throw IllegalStateException("The app does not declare the service '${service.key}'")
}

/**
 * An app: who it is, which services it needs, and the actions it offers.
 *
 * Pure declaration. A [Runtime] logs it in (its [identifier], [version], [scopes] and
 * [requirements] are the manifest it shows the deployment) and serves its [actions].
 */
class App(val identifier: String, val version: String) {
    var description: String? = null
        private set

    /** What the login asks for. */
    val scopes: MutableList<String> = mutableListOf("openid")

    /** The services the app needs. Every app needs rekuest: it is what assigns its actions. */
    val requirements: MutableList<Requirement> =
            mutableListOf(Requirement(key = REKUEST, service = "live.arkitekt.rekuest"))

    /** The declared services whose clients a [Runtime] builds, in order. */
    val services: MutableList<Service<*>> = mutableListOf()

    /** What is made at login for `@Inject` parameters, beside the services' clients. */
    val providers: MutableList<(Context) -> Any> = mutableListOf()

    /** The actions, by key, in the order they were declared. */
    val actions: LinkedHashMap<String, RegisteredAction> = LinkedHashMap()

    /** The states, by name, each with the value it starts from. */
    val states: LinkedHashMap<String, DeclaredState<*>> = LinkedHashMap()

    /** The bloks, by key. */
    val bloks: LinkedHashMap<String, DeclaredBlok> = LinkedHashMap()

    fun description(text: String): App = apply { description = text }

    /** Need a service and have its client built at login: `context[service]` in a handler. */
    fun <T : Any> service(service: Service<T>): App = apply {
        requires(service.key, service.service, service.optional)
        services += service
    }

    /** Need a service without a client of its own. The login fails without it, unless optional. */
    fun requires(key: String, service: String, optional: Boolean = false): App = apply {
        require(requirements.none { it.key == key }) { "$identifier already requires '$key'" }
        requirements += Requirement(key = key, service = service, optional = optional)
    }

    /** Make [value] available to `@Inject` parameters of its type (a host object, say). */
    fun provide(value: Any): App = apply { providers += { value } }

    /** The same, for something built from the login: its clients are in the [Context]. */
    fun provide(factory: (Context) -> Any): App = apply { providers += factory }

    /** Offer an action written as an `@Action` function: `action(ShowLensAction)`. */
    fun action(declared: ActionDeclaration): App = apply {
        val action = declared.declare(version)
        require(action.key !in actions) { "$identifier already declares an action '${action.key}'" }
        actions[action.key] = action
    }

    /** Offer several `@Action` functions at once. */
    fun actions(vararg declared: ActionDeclaration): App = apply { declared.forEach { action(it) } }

    /**
     * Offer an action. [key] is what the server assigns by, [name] and [description] what a
     * person reads; [ports] declares the arguments and returns, and [handler] does the work.
     */
    fun action(
            key: String,
            name: String,
            description: String? = null,
            ports: Ports.() -> Unit = {},
            handler: Handler
    ): App = apply {
        require(key !in actions) { "$identifier already declares an action '$key'" }
        val declared = Ports().apply(ports)
        actions[key] =
                RegisteredAction(
                        DefinitionInput(
                                key = key,
                                version = version,
                                name = name,
                                description = Optional.presentIfNotNull(description),
                                args = Optional.present(declared.args),
                                returns = Optional.present(declared.returns),
                                kind = ActionKind.FUNCTION
                        ),
                        handler
                )
    }

    /**
     * Hold a state, written as a `@State` class: `state(StagePositionState, StagePosition())`.
     * [initial] is what it is until something changes it.
     */
    fun <T> state(declaration: StateDeclaration<T>, initial: T): App = apply {
        require(declaration.name !in states) { "$identifier already declares a state '${declaration.name}'" }
        states[declaration.name] = DeclaredState(declaration, initial)
    }

    /**
     * Ship a blok: UI written in BSX, one `<Blok kind=… context=…>` element that reads the
     * app's states (`self.<State>.<field>`) and calls its actions (`self.<action>(…)`).
     *
     * The [source] is all the app says of it. The server parses it and works out which states
     * and actions it depends on; one that does not parse, or reads what the app does not
     * declare, fails the registration ([Runtime.connect] throws, with the server's finding).
     * Write it as a literal, not as a string built at run time.
     *
     * @param default make it what a UI opens first for this app.
     * @param forModel the model (`sha256:…`) it decorates, at [forNode] or the model's root.
     * @param args the items it is opened for, read in the source as `args.<key>`.
     */
    fun blok(
            key: String,
            source: String,
            description: String? = null,
            default: Boolean = false,
            forModel: String? = null,
            forNode: String? = null,
            args: Ports.() -> Unit = {}
    ): App = apply {
        require(key !in bloks) { "$identifier already declares a blok '$key'" }
        bloks[key] = DeclaredBlok(key, source, description, default, forModel, forNode, Ports().apply(args).args)
    }

    /**
     * Everything the app declares, as it is registered: its actions with the locks they run
     * under and the states they change, its states, its locks and its bloks.
     */
    fun declaration(): ImplementAgentInput {
        val lockKeys = actions.values.flatMap { it.locks }.distinct()
        return ImplementAgentInput(
                description = Optional.presentIfNotNull(description),
                implementations =
                        Optional.present(
                                actions.values.map { action ->
                                    ImplementationInput(
                                            definition = action.definition,
                                            `interface` = action.key,
                                            locks = if (action.locks.isEmpty()) Optional.absent() else Optional.present(action.locks),
                                            manipulates =
                                                    if (action.manipulates.isEmpty()) Optional.absent()
                                                    else Optional.present(action.manipulates)
                                    )
                                }
                        ),
                states =
                        Optional.present(
                                states.values.map { state ->
                                    StateImplementationInput(
                                            `interface` = state.declaration.name,
                                            definition =
                                                    StateDefinitionInput(
                                                            ports = state.declaration.ports,
                                                            name = state.declaration.name
                                                    )
                                    )
                                }
                        ),
                locks =
                        Optional.present(
                                lockKeys.map { key ->
                                    LockImplementationInput(
                                            key = key,
                                            definition =
                                                    LockDefinitionInput(
                                                            key = key,
                                                            description = Optional.present("Lock definition for $key")
                                                    )
                                    )
                                }
                        ),
                // A blok is its source: its context and what it reads are the server's to find.
                bloks =
                        Optional.present(
                                bloks.values.map { blok ->
                                    BlokImplementationInput(
                                            key = blok.key,
                                            source = Optional.present(blok.source),
                                            vocab = BLOK_VOCAB,
                                            description = Optional.presentIfNotNull(blok.description),
                                            default = if (blok.default) Optional.present(true) else Optional.absent(),
                                            forModel = Optional.presentIfNotNull(blok.forModel),
                                            forNode = Optional.presentIfNotNull(blok.forNode),
                                            args = if (blok.args.isEmpty()) Optional.absent() else Optional.present(blok.args)
                                    )
                                }
                        )
        )
    }

    companion object {
        /** The key every app's login names rekuest by. */
        const val REKUEST = "rekuest"
    }
}
