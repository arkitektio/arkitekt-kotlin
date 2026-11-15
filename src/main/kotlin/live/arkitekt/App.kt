package live.arkitekt

import com.apollographql.apollo.api.Optional
import kotlinx.serialization.json.JsonElement
import live.arkitekt.rekuest.graphql.type.ActionKind
import live.arkitekt.rekuest.graphql.type.ArgPortInput
import live.arkitekt.rekuest.graphql.type.DefinitionInput
import live.arkitekt.rekuest.graphql.type.PortKind
import live.arkitekt.rekuest.graphql.type.ReturnPortInput

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

/** One action an app offers: what it takes and returns, and the function that does it. */
class Action(val definition: DefinitionInput, val handler: Handler) {
    val key: String get() = definition.key
}

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

    /** The actions, by key, in the order they were declared. */
    val actions: LinkedHashMap<String, Action> = LinkedHashMap()

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
                Action(
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

    companion object {
        /** The key every app's login names rekuest by. */
        const val REKUEST = "rekuest"
    }
}
