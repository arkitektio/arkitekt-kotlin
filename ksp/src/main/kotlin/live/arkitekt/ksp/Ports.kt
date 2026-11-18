package live.arkitekt.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias

// How a Kotlin type becomes a port, for both processors: an action's parameters and return
// (ActionProcessor) and a state's properties (StateProcessor).

internal const val STRUCTURE = "live.arkitekt.Structure"
internal const val REQUIRES = "live.arkitekt.Requires"
internal const val PROVIDES = "live.arkitekt.Provides"
internal const val STATE = "live.arkitekt.State"

/** Something that cannot be declared as written, said at the place it is wrong. */
internal class Refused(message: String, val at: KSNode) : Exception(message)

// ---- Types ------------------------------------------------------------------------------

internal sealed class Kind {
    data class Scalar(val port: String, val reader: String) : Kind()
    data class ListOf(val item: Plan) : Kind()
    data class Struct(val qualified: String) : Kind()
}

/** A type with its aliases followed: the type they name, and what they said on the way. */
internal class Unaliased(val type: KSType, val nullable: Boolean, val markers: List<KSAnnotation>)

internal fun unalias(type: KSType): Unaliased {
    var current = type
    var nullable = type.isMarkedNullable
    val markers = mutableListOf<KSAnnotation>()
    while (true) {
        val alias = current.declaration as? KSTypeAlias ?: break
        markers += alias.annotations
        current = alias.type.resolve()
        nullable = nullable || current.isMarkedNullable
    }
    return Unaliased(current.makeNotNullable(), nullable, markers)
}

/**
 * How one port (or a list's item) travels: its kind, whether it may be null, and the field
 * holding its constraints, if it states any.
 */
internal class Plan(val kind: Kind, val nullable: Boolean, val constraints: String?)

/**
 * Plan the port of [type]. Its constraints are the [side]'s markers (`Requires` for an
 * argument, `Provides` for a return) among [markers] (written at the use) and on the type
 * aliases [type] goes through (written on a spec).
 */
internal fun plan(type: KSType, markers: List<KSAnnotation>, side: String, fields: MutableList<String>, at: KSNode): Plan {
    val unaliased = unalias(type)
    val constraints =
            (markers + unaliased.markers)
                    .filter { it.annotationType.resolve().declaration.qualifiedName?.asString() == side }
                    .map { marker ->
                        val operator = marker.arguments.first { it.name?.asString() == "operator" }.value
                        "live.arkitekt.Constraint(${marker.string("key").literal()}, " +
                                "live.arkitekt.DescriptorOperator.${operator.toString().substringAfterLast('.')}, " +
                                "${(marker.arguments.firstOrNull { it.name?.asString() == "value" }?.value as? String ?: "true").literal()})"
                    }
    val field =
            if (constraints.isEmpty()) null
            else "constraints${fields.size}".also {
                fields += "private val $it = listOf(${constraints.joinToString(", ")})"
            }
    return Plan(kind(unaliased.type, side, fields, at), unaliased.nullable, field)
}

internal fun kind(type: KSType, side: String, fields: MutableList<String>, at: KSNode): Kind {
    val declaration = type.declaration
    val qualified = declaration.qualifiedName?.asString() ?: throw Refused("a type without a name", at)
    return when (qualified) {
        "kotlin.String" -> Kind.Scalar("STRING", "string")
        "kotlin.Long" -> Kind.Scalar("INT", "long")
        "kotlin.Int" -> Kind.Scalar("INT", "int")
        "kotlin.Double" -> Kind.Scalar("FLOAT", "double")
        "kotlin.Float" -> Kind.Scalar("FLOAT", "float")
        "kotlin.Boolean" -> Kind.Scalar("BOOL", "boolean")
        "kotlin.collections.List" ->
                Kind.ListOf(
                        plan(
                                type.arguments.firstOrNull()?.type?.resolve()
                                        ?: throw Refused("a List without an item type", at),
                                emptyList(),
                                side,
                                fields,
                                at
                        )
                )
        else -> {
            val companion =
                    (declaration as? KSClassDeclaration)?.declarations
                            ?.filterIsInstance<KSClassDeclaration>()
                            ?.firstOrNull { it.isCompanionObject }
            val isStructure =
                    companion?.getAllSuperTypes()?.any {
                        it.declaration.qualifiedName?.asString() == STRUCTURE
                    } == true
            if (!isStructure) {
                throw Refused(
                        "$qualified cannot be a port. A port is a String, Int, Long, Float, Double, " +
                                "Boolean, a List of those, or a class whose companion object is a " +
                                "live.arkitekt.Structure. Mark the parameter @Inject if the app fills it.",
                        at
                )
            }
            Kind.Struct(qualified)
        }
    }
}

internal fun KSClassDeclaration.getAllSuperTypes(): Sequence<KSType> = sequence {
    for (reference in superTypes) {
        val resolved = reference.resolve()
        yield(resolved)
        (resolved.declaration as? KSClassDeclaration)?.let { yieldAll(it.getAllSuperTypes()) }
    }
}

/** A port, as the constructor call of [portClass] (`ArgPortInput` or `ReturnPortInput`). */
internal fun port(portClass: String, key: String, plan: Plan, nullable: Boolean, description: String?): String {
    // An argument keeps what it requires, a return what it provides.
    val constrained =
            when {
                plan.constraints == null -> ""
                portClass == "ArgPortInput" -> ", requires = Optional.present(${plan.constraints}.map { it.requires() })"
                else -> ", provides = Optional.present(${plan.constraints}.map { it.provides() })"
            }
    val base =
            "key = ${key.literal()}, description = ${optional(description)}, " +
                    "nullable = Optional.present($nullable)$constrained"
    return when (val kind = plan.kind) {
        is Kind.Scalar -> "$portClass(kind = PortKind.${kind.port}, $base)"
        is Kind.Struct ->
                "$portClass(kind = PortKind.STRUCTURE, identifier = Optional.present(${kind.qualified}.identifier), $base)"
        is Kind.ListOf -> {
            // A list has one child, keyed "..." as the other SDKs key it.
            val child = port(portClass, "...", kind.item, kind.item.nullable, null)
            "$portClass(kind = PortKind.LIST, children = Optional.present(listOf($child)), $base)"
        }
    }
}

/**
 * The expression that reads a (non-null) value from the JSON element [element]. A structure
 * whose port states constraints is tested against them once it is expanded.
 */
internal fun decode(plan: Plan, element: String, portKey: String): String =
        when (val kind = plan.kind) {
            is Kind.Scalar -> "live.arkitekt.Wire.${kind.reader}($element)"
            is Kind.Struct -> {
                val expand = "live.arkitekt.Wire.expand(${kind.qualified}, context, $element)"
                if (plan.constraints == null) expand
                else "$expand.also { expanded -> live.arkitekt.Wire.check(${kind.qualified}, expanded, " +
                        "${plan.constraints}, \"requires\", ${portKey.literal()}) }"
            }
            is Kind.ListOf -> {
                val inner = decode(kind.item, "item", portKey)
                if (kind.item.nullable)
                        "live.arkitekt.Wire.list($element) { item -> if (item is kotlinx.serialization.json.JsonNull) null else $inner }"
                else "live.arkitekt.Wire.list($element) { item -> $inner }"
            }
        }

/**
 * The expression that writes the (non-null) [value] as a JSON element. A structure whose
 * port states constraints is tested against them before it is shrunk.
 */
internal fun encode(plan: Plan, value: String, portKey: String): String =
        when (val kind = plan.kind) {
            is Kind.Scalar -> "live.arkitekt.Wire.json($value)"
            is Kind.Struct -> {
                val checked =
                        if (plan.constraints == null) value
                        else "$value.also { shrunk -> live.arkitekt.Wire.check(${kind.qualified}, shrunk, " +
                                "${plan.constraints}, \"provides\", ${portKey.literal()}) }"
                "live.arkitekt.Wire.shrink(${kind.qualified}, $checked)"
            }
            is Kind.ListOf -> {
                val inner = encode(kind.item, "item", portKey)
                if (kind.item.nullable)
                        "kotlinx.serialization.json.JsonArray($value.map { item -> if (item == null) kotlinx.serialization.json.JsonNull else $inner })"
                else "kotlinx.serialization.json.JsonArray($value.map { item -> $inner })"
            }
        }

internal fun KSType.className(at: KSNode): String {
    if ((declaration as? KSClassDeclaration)?.classKind == ClassKind.ANNOTATION_CLASS) {
        throw Refused("an annotation cannot be injected", at)
    }
    return declaration.qualifiedName?.asString() ?: throw Refused("a type without a name", at)
}

internal fun optional(text: String?): String =
        if (text.isNullOrBlank()) "Optional.absent()" else "Optional.present(${text.literal()})"
