package live.arkitekt

import com.apollographql.apollo.api.Optional
import kotlinx.serialization.json.JsonElement
import live.arkitekt.rekuest.graphql.type.AssignWidgetInput
import live.arkitekt.rekuest.graphql.type.AssignWidgetKind
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

// Actions from plain functions, as `@register` does in Python and `#[action]` in Rust:
//
//     /**
//      * Show Lens
//      *
//      * Show a lens in the viewer.
//      *
//      * @param lens The lens to show
//      * @return The lens that was shown
//      */
//     @Action
//     suspend fun showLens(lens: Lens, @Inject viewer: Viewer): Lens { … }
//
//     val app = App("imagej", "0.1.0").action(ShowLensAction)
//
// The processor (`arkitekt-kotlin-ksp`, applied with KSP) reads the function at compile time and
// writes `ShowLensAction` beside it: the definition, with a port per parameter, and the handler
// that turns the arguments off the wire into the function's parameters and its result back.
// Nothing is looked up by reflection at run time.

/**
 * Make an action of a top-level function.
 *
 * - **Ports** come from the signature. `String`, `Int`/`Long`, `Float`/`Double`, `Boolean`, a
 *   `List` of those, and any class with a [Structure] as its companion. A nullable type is a
 *   nullable port; so is a parameter with a default value, which is used when nothing is sent.
 * - **Words** come from the KDoc: with more than one paragraph the first is the action's name
 *   and the rest its description, otherwise the name is the function's (`showLens` → "Show
 *   Lens"). `@param` describes a port, `@return` the result.
 * - A parameter marked [Inject] is not a port: it is filled from the app (see [Context.inject]).
 *
 * - A [Task] parameter is the running task (progress, logs); a [StateMut] or [StateRef]
 *   parameter is one of the app's states. Neither is a port.
 *
 * @param key what the server assigns the action by; the function's name in snake case if empty.
 * @param name what a person reads; overrides the KDoc.
 * @param description overrides the KDoc.
 * @param locks the locks a task holds while it runs, beside those of the states it takes: two
 *   tasks that name the same lock run one after the other.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class Action(
        val key: String = "",
        val name: String = "",
        val description: String = "",
        val locks: Array<String> = []
)

/**
 * A parameter the app fills, not the caller: the [Context], a declared service's client, or
 * something the app provides ([App.provide]). Found by its type.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class Inject

/** Name the action's return (`return0` otherwise), and describe it in place of the KDoc. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class Returns(val key: String, val description: String = "")

/**
 * How a class travels as a `STRUCTURE` port: by an id, under an identifier (`@mikro/lens`).
 *
 * Implemented by the class's companion object, which is how the processor finds it:
 *
 *     class Lens(val id: String) {
 *         companion object : IdStructure<Lens>("@mikro/lens", ::Lens, Lens::id)
 *     }
 */
interface Structure<T> {
    /** What the deployment knows the structure as. */
    val identifier: String

    /** The value an id stands for. May ask a service: the [context] has the clients. */
    suspend fun expand(context: Context, id: String): T

    /** The id a value travels as. */
    fun shrink(value: T): String

    /**
     * The descriptors of a value: what a port's [Requires] and [Provides] are tested against
     * when the value crosses it. A key left out is not tested; nothing by default.
     *
     * Neither suspending nor handed the [Context], on purpose: describing never asks a server.
     * The descriptors are fetched with the object when it is expanded ([expand] selects them)
     * and read off the value here. A second round trip to describe is an antipattern.
     */
    fun describe(value: T): Map<String, JsonElement> = emptyMap()

    /**
     * The widget every argument port of this structure is drawn with: how a person picks one
     * in a form. The Python SDK's structures declare theirs the same way; usually a
     * [searchWidget] over the service that holds them. None by default (the frontend then has
     * only an id to ask for).
     */
    val widget: AssignWidgetInput? get() = null
}

/**
 * A widget that searches [ward] (the service, as `mikro`) with [query] for the value of a port:
 * a GraphQL document answering `options { value label }` for `$search`, `$values` and, when it
 * declares them, the port's own `$requires`. The structure's service publishes these queries;
 * take its document as it is written there, so every SDK sends the same widget.
 */
fun searchWidget(query: String, ward: String): AssignWidgetInput =
        AssignWidgetInput(
                kind = AssignWidgetKind.SEARCH,
                query = Optional.present(query),
                ward = Optional.present(ward)
        )

/** A [Structure] that is its id and nothing more: nothing is fetched to expand it. */
open class IdStructure<T>(
        override val identifier: String,
        private val of: (String) -> T,
        private val id: (T) -> String
) : Structure<T> {
    override suspend fun expand(context: Context, id: String): T = of(id)
    override fun shrink(value: T): String = id(value)
}

/** An action as the processor writes it: registered on an app with [App.action]. */
interface ActionDeclaration {
    /** The action under an app's [version]. */
    fun declare(version: String): RegisteredAction
}

/**
 * Values off the wire and back, for the code the processor writes. Not meant to be called by
 * hand, but nothing in it is private to generated code.
 */
object Wire {
    /** Whether an argument was sent (and is not null). */
    fun present(args: Map<String, JsonElement?>, key: String): Boolean =
            args[key].let { it != null && it !is JsonNull }

    /** An argument that must be there. */
    fun required(args: Map<String, JsonElement?>, key: String): JsonElement =
            args[key]?.takeUnless { it is JsonNull }
                    ?: throw IllegalArgumentException("Missing argument '$key'")

    /** An argument that may be absent or null. */
    fun optional(args: Map<String, JsonElement?>, key: String): JsonElement? =
            args[key]?.takeUnless { it is JsonNull }

    fun string(e: JsonElement): String = e.jsonPrimitive.content
    fun long(e: JsonElement): Long = e.jsonPrimitive.long
    fun int(e: JsonElement): Int = e.jsonPrimitive.int
    fun double(e: JsonElement): Double = e.jsonPrimitive.double
    fun float(e: JsonElement): Float = e.jsonPrimitive.float
    fun boolean(e: JsonElement): Boolean = e.jsonPrimitive.boolean

    /** The items of a list. Inline, so an item may be expanded (a suspend call) in place. */
    inline fun <T> list(e: JsonElement, item: (JsonElement) -> T): List<T> = e.jsonArray.map(item)

    /**
     * The id of a structure argument. It arrives either as a bare id or, more commonly, in the
     * shrunk form `{"__identifier": "@mikro/lens", "object": "<id>"}`.
     */
    fun structureId(e: JsonElement): String =
            when (e) {
                is JsonObject ->
                        e["object"]?.jsonPrimitive?.contentOrNull
                                ?: throw IllegalArgumentException("Structure argument without 'object': $e")
                else -> e.jsonPrimitive.content
            }

    suspend fun <T> expand(structure: Structure<T>, context: Context, e: JsonElement): T =
            structure.expand(context, structureId(e))

    /** A structure as it is returned: a bare id is refused, it must name its identifier. */
    fun <T> shrink(structure: Structure<T>, value: T): JsonElement = buildJsonObject {
        put("__identifier", structure.identifier)
        put("object", structure.shrink(value))
    }

    /**
     * Test a structure crossing a port against the port's constraints, by what the structure
     * says of the value ([Structure.describe]). [side] is "requires" or "provides".
     *
     * @throws IllegalArgumentException naming the constraints the value breaks: the task fails.
     */
    fun <T> check(
            structure: Structure<T>,
            value: T,
            constraints: List<Constraint>,
            side: String,
            port: String
    ) {
        val described = structure.describe(value)
        if (described.isEmpty()) return
        val broken = Descriptors.unfulfilled(constraints, described)
        if (broken.isNotEmpty()) {
            throw IllegalArgumentException(
                    "'$port' is not what the port $side: ${broken.joinToString("; ")}"
            )
        }
    }

    fun json(value: String): JsonElement = JsonPrimitive(value)
    fun json(value: Number): JsonElement = JsonPrimitive(value)
    fun json(value: Boolean): JsonElement = JsonPrimitive(value)
}
