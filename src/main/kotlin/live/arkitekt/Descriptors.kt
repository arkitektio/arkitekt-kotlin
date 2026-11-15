package live.arkitekt

import com.apollographql.apollo.api.Optional
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import live.arkitekt.rekuest.graphql.type.ProvidesInput
import live.arkitekt.rekuest.graphql.type.DescriptorOperator as WireOperator
import live.arkitekt.rekuest.graphql.type.RequiresInput

// What a port requires of a structure, and what it says of one it returns.
//
// A structure has descriptors: facts about one object under a key a service declares
// (`@mikro/n_space_axes` = 3). A port states constraints over them. On an argument they are
// what it *requires*: a picker offers only objects that fit, and the server matches on them.
// On a return they are what the action *provides*: what it says it made.
//
// As in Python (`Annotated[Lens, Requires(…), Provides(…)]`), a spec is a type alias carrying
// both, and each port keeps its own side:
//
//     @Requires(N_SPACE_AXES, DescriptorOperator.EQUALS, "3")
//     @Provides(N_SPACE_AXES, DescriptorOperator.EQUALS, "3")
//     typealias Volume = Lens
//
//     @Action
//     suspend fun deconvolve(image: Volume): Volume { … }
//
// A constraint can also be written where it is used: `@Requires` on a parameter, `@Provides`
// on the function.

/** How a descriptor's value is compared with a constraint's. The server's operators. */
enum class DescriptorOperator {
    /** Equality. (It is equality on the server too, not a pattern.) */
    MATCHES,
    /** The key is there; with the value `false`, that it is not. */
    EXISTS,
    LTE,
    GTE,
    EQUALS,
    /** The descriptor is a list holding the value. */
    CONTAINS,
    NOT_EQUALS,
    /** The descriptor is one of the listed values. */
    IN,
    NOT_IN
}

/**
 * What an argument's structure must be. On a parameter, or on a type alias of a structure.
 *
 * @param value the constraint's value as JSON text, since an annotation cannot hold any value:
 *   `"3"`, `"\"tif\""`, `"[\"tif\", \"tiff\"]"`.
 */
@Repeatable
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.TYPEALIAS)
@Retention(AnnotationRetention.SOURCE)
annotation class Requires(val key: String, val operator: DescriptorOperator, val value: String = "true")

/**
 * What a returned structure is said to be. On the function, or on a type alias of a structure.
 *
 * @param value the constraint's value as JSON text (see [Requires]).
 */
@Repeatable
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPEALIAS)
@Retention(AnnotationRetention.SOURCE)
annotation class Provides(val key: String, val operator: DescriptorOperator, val value: String = "true")

/** One constraint of a port, as the processor writes it from a [Requires] or [Provides]. */
class Constraint(val key: String, val operator: DescriptorOperator, val value: JsonElement) {
    /** With the value as JSON text, the way the annotations hold it. */
    constructor(key: String, operator: DescriptorOperator, json: String) : this(
            key,
            operator,
            try {
                Json.parseToJsonElement(json)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                        "The constraint on '$key' has a value that is not JSON: $json (a string is written \"\\\"text\\\"\")"
                )
            }
    )

    fun requires(): RequiresInput =
            RequiresInput(key, WireOperator.safeValueOf(operator.name), Optional.presentIfNotNull(plain(value)))

    fun provides(): ProvidesInput =
            ProvidesInput(key, WireOperator.safeValueOf(operator.name), Optional.presentIfNotNull(plain(value)))

    override fun toString(): String = "$key ${operator.name} $value"

    private fun plain(element: JsonElement): Any? =
            when (element) {
                is JsonNull -> null
                is JsonArray -> element.map { plain(it) }
                is JsonObject -> element.mapValues { plain(it.value) }
                is JsonPrimitive ->
                        if (element.isString) element.content
                        else element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.content
            }
}

/** Testing an object's descriptors against constraints, with the server's meaning of each operator. */
object Descriptors {
    /**
     * Whether a descriptor's value satisfies one operator.
     *
     * @throws IllegalArgumentException when the operator cannot be applied to these values
     *   (ordering a string, membership without a list).
     */
    fun holds(operator: DescriptorOperator, actual: JsonElement, expected: JsonElement): Boolean =
            when (operator) {
                // No value, or true, asks for presence; false asks for absence.
                DescriptorOperator.EXISTS -> (expected as? JsonPrimitive)?.booleanOrNull != false
                DescriptorOperator.EQUALS, DescriptorOperator.MATCHES -> same(actual, expected)
                DescriptorOperator.NOT_EQUALS -> !same(actual, expected)
                DescriptorOperator.GTE, DescriptorOperator.LTE -> {
                    val a = number(actual)
                    val b = number(expected)
                    require(a != null && b != null) {
                        "${operator.name} orders numbers; $actual and $expected are not both numbers"
                    }
                    if (operator == DescriptorOperator.GTE) a >= b else a <= b
                }
                DescriptorOperator.IN, DescriptorOperator.NOT_IN -> {
                    require(expected is JsonArray) { "${operator.name} needs a list of allowed values, got $expected" }
                    val found = expected.any { same(actual, it) }
                    if (operator == DescriptorOperator.IN) found else !found
                }
                DescriptorOperator.CONTAINS -> {
                    require(actual is JsonArray) { "CONTAINS needs a list descriptor, got $actual" }
                    actual.any { same(it, expected) }
                }
            }

    /**
     * Every constraint these descriptors do not satisfy, one line each.
     *
     * A constraint on a key the descriptors do not hold is skipped: it cannot be tested from
     * the object (it is provenance, which only whoever made the object can state).
     */
    fun unfulfilled(constraints: List<Constraint>, described: Map<String, JsonElement>): List<String> =
            constraints.mapNotNull { constraint ->
                val actual = described[constraint.key] ?: return@mapNotNull null
                if (holds(constraint.operator, actual, constraint.value)) null
                else "$constraint (actual $actual)"
            }

    private fun number(element: JsonElement): Double? =
            (element as? JsonPrimitive)?.takeUnless { it.isString || it.booleanOrNull != null }?.doubleOrNull

    /** Equal, with 3 and 3.0 the same number. */
    private fun same(a: JsonElement, b: JsonElement): Boolean {
        val x = number(a)
        val y = number(b)
        return if (x != null && y != null) x == y else a == b
    }
}
