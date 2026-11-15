package live.arkitekt.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier

private const val ACTION = "live.arkitekt.Action"
private const val INJECT = "live.arkitekt.Inject"
private const val RETURNS = "live.arkitekt.Returns"
private const val STRUCTURE = "live.arkitekt.Structure"

/** A parameter with a default value is tried in every present/absent combination: 2^n calls. */
private const val MAX_DEFAULTS = 4

class ActionProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
            ActionProcessor(environment.codeGenerator, environment.logger)
}

/** Something about a function that cannot be an action, said at the place it is wrong. */
private class Refused(message: String, val at: KSNode) : Exception(message)

/**
 * Writes, for every top-level function marked `@Action`, an `object <Name>Action :
 * ActionDeclaration` in the function's package: its definition (a port per parameter) and the
 * handler that reads the arguments off the wire, calls the function and sends its result back.
 */
class ActionProcessor(private val generator: CodeGenerator, private val logger: KSPLogger) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val (ready, later) = resolver.getSymbolsWithAnnotation(ACTION).partition { it.validateSignature() }
        for (symbol in ready) {
            val function = symbol as? KSFunctionDeclaration ?: continue
            try {
                write(function)
            } catch (e: Refused) {
                logger.error("@Action ${function.simpleName.asString()}: ${e.message}", e.at)
            }
        }
        return later
    }

    /** A function whose types do not all resolve yet waits for the next round. */
    private fun KSAnnotated.validateSignature(): Boolean {
        val function = this as? KSFunctionDeclaration ?: return true
        return function.parameters.all { !it.type.resolve().isError } &&
                function.returnType?.resolve()?.isError != true
    }

    private fun write(function: KSFunctionDeclaration) {
        if (function.parentDeclaration != null) {
            throw Refused("only a top-level function can be an action", function)
        }
        if (Modifier.PRIVATE in function.modifiers) {
            throw Refused("a private function cannot be called from its generated declaration", function)
        }
        if (function.extensionReceiver != null) {
            throw Refused("an extension function cannot be an action", function)
        }
        if (function.typeParameters.isNotEmpty()) {
            throw Refused("a generic function cannot be an action", function)
        }

        val annotation = function.annotation(ACTION)!!
        val doc = Doc.parse(function.docString)
        val functionName = function.simpleName.asString()
        val key = annotation.string("key").ifEmpty { functionName.snakeCase() }
        val name = annotation.string("name").ifEmpty { doc.name ?: functionName.titleCase() }
        val description = annotation.string("description").ifEmpty { doc.description }

        // Parameters: a port each, unless injected.
        val ports = mutableListOf<String>()
        val fixed = mutableListOf<Pair<String, String>>() // parameter name -> expression
        val defaulted = mutableListOf<Pair<String, String>>() // parameter name -> port key
        val decoders = mutableMapOf<String, String>()
        for (parameter in function.parameters) {
            val parameterName =
                    parameter.name?.asString() ?: throw Refused("a parameter without a name", parameter)
            if (parameter.isVararg) throw Refused("a vararg cannot be a port: take a List", parameter)
            val type = parameter.type.resolve()
            if (parameter.has(INJECT)) {
                fixed += parameterName to "context.inject(${type.className(parameter)}::class.java)"
                continue
            }
            val nullable = type.isMarkedNullable || parameter.hasDefault
            ports += port("ArgPortInput", parameterName, type, nullable, doc.params[parameterName], parameter)
            val decode = decode(type.makeNotNullable(), "it", parameter)
            when {
                parameter.hasDefault -> {
                    defaulted += parameterName to parameterName
                    decoders[parameterName] =
                            "live.arkitekt.Wire.required(args, ${parameterName.literal()}).let { $decode }"
                }
                type.isMarkedNullable ->
                        fixed += parameterName to
                                "live.arkitekt.Wire.optional(args, ${parameterName.literal()})?.let { $decode }"
                else ->
                        fixed += parameterName to
                                "live.arkitekt.Wire.required(args, ${parameterName.literal()}).let { $decode }"
            }
        }
        if (defaulted.size > MAX_DEFAULTS) {
            throw Refused(
                    "more than $MAX_DEFAULTS parameters with a default value: make some nullable instead",
                    function
            )
        }

        // The return: none for Unit, one port otherwise.
        val returned = function.returnType?.resolve() ?: throw Refused("no return type", function)
        val returns = function.annotation(RETURNS)
        val returnKey = returns?.string("key")?.ifEmpty { null } ?: "return0"
        val returnDescription = returns?.string("description")?.ifEmpty { null } ?: doc.returns
        val isUnit = returned.declaration.qualifiedName?.asString() == "kotlin.Unit"
        val returnPorts =
                if (isUnit) emptyList()
                else listOf(port("ReturnPortInput", returnKey, returned, returned.isMarkedNullable, returnDescription, function))

        val target = function.qualifiedName?.asString() ?: throw Refused("a function without a name", function)

        // One call per combination of sent and unsent defaults: an unsent one is left out of
        // the call, so the function's own default applies.
        fun call(sent: Set<String>): String {
            val arguments =
                    fixed.map { (name, expression) -> "$name = $expression" } +
                            defaulted.filter { it.first in sent }.map { (name, _) -> "$name = ${decoders[name]}" }
            return "$target(${arguments.joinToString(", ")})"
        }
        val invocation =
                if (defaulted.isEmpty()) call(emptySet())
                else buildString {
                    appendLine("when {")
                    val names = defaulted.map { it.first }
                    // Most arguments sent first; the last branch (none sent) is the `else`.
                    for (mask in (1 shl names.size) - 1 downTo 1) {
                        val sent = names.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
                        val condition =
                                names.joinToString(" && ") { name ->
                                    val present = "live.arkitekt.Wire.present(args, ${name.literal()})"
                                    if (name in sent) present else "!$present"
                                }
                        appendLine("                        $condition -> ${call(sent)}")
                    }
                    appendLine("                        else -> ${call(emptySet())}")
                    append("                    }")
                }
        val body =
                if (isUnit) "$invocation\n                    emptyMap()"
                else {
                    val encode =
                            if (returned.isMarkedNullable)
                                    "result?.let { ${encode(returned.makeNotNullable(), "it", function)} } ?: kotlinx.serialization.json.JsonNull"
                            else encode(returned, "result", function)
                    "val result = $invocation\n                    mapOf(${returnKey.literal()} to $encode)"
                }

        val objectName = functionName.replaceFirstChar { it.uppercase() } + "Action"
        val packageName = function.packageName.asString()
        val file = function.containingFile
        val source = buildString {
            appendLine("// Written by arkitekt-kotlin's processor from the @Action function `$functionName`.")
            appendLine("// Do not edit: change the function, its KDoc or its annotations instead.")
            if (packageName.isNotEmpty()) appendLine("package $packageName")
            appendLine()
            appendLine("import com.apollographql.apollo.api.Optional")
            appendLine("import live.arkitekt.rekuest.graphql.type.ActionKind")
            appendLine("import live.arkitekt.rekuest.graphql.type.ArgPortInput")
            appendLine("import live.arkitekt.rekuest.graphql.type.DefinitionInput")
            appendLine("import live.arkitekt.rekuest.graphql.type.PortKind")
            appendLine("import live.arkitekt.rekuest.graphql.type.ReturnPortInput")
            appendLine()
            appendLine("/** The action `$key`, made of [$functionName]. Register it with `App.action($objectName)`. */")
            appendLine("object $objectName : live.arkitekt.ActionDeclaration {")
            appendLine("    override fun declare(version: String): live.arkitekt.RegisteredAction =")
            appendLine("            live.arkitekt.RegisteredAction(")
            appendLine("                DefinitionInput(")
            appendLine("                    key = ${key.literal()},")
            appendLine("                    version = version,")
            appendLine("                    name = ${name.literal()},")
            appendLine("                    description = ${optional(description)},")
            appendLine("                    args = Optional.present(listOf(${ports.joinToString(", ")})),")
            appendLine("                    returns = Optional.present(listOf(${returnPorts.joinToString(", ")})),")
            appendLine("                    kind = ActionKind.FUNCTION")
            appendLine("                ),")
            appendLine("                handler = { context, args ->")
            appendLine("                    $body")
            appendLine("                }")
            appendLine("            )")
            appendLine("}")
        }
        generator
                .createNewFile(
                        if (file != null) Dependencies(false, file) else Dependencies(false),
                        packageName,
                        objectName
                )
                .use { it.write(source.toByteArray(Charsets.UTF_8)) }
    }

    // ---- Types ------------------------------------------------------------------------------

    private sealed class Kind {
        data class Scalar(val port: String, val reader: String) : Kind()
        data class ListOf(val item: KSType) : Kind()
        data class Struct(val qualified: String) : Kind()
    }

    private fun kind(type: KSType, at: KSNode): Kind {
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
                            type.arguments.firstOrNull()?.type?.resolve()
                                    ?: throw Refused("a List without an item type", at)
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

    private fun KSClassDeclaration.getAllSuperTypes(): Sequence<KSType> = sequence {
        for (reference in superTypes) {
            val resolved = reference.resolve()
            yield(resolved)
            (resolved.declaration as? KSClassDeclaration)?.let { yieldAll(it.getAllSuperTypes()) }
        }
    }

    /** A port, as the constructor call of [portClass] (`ArgPortInput` or `ReturnPortInput`). */
    private fun port(
            portClass: String,
            key: String,
            type: KSType,
            nullable: Boolean,
            description: String?,
            at: KSNode
    ): String {
        val base = "key = ${key.literal()}, description = ${optional(description)}, nullable = Optional.present($nullable)"
        return when (val kind = kind(type.makeNotNullable(), at)) {
            is Kind.Scalar -> "$portClass(kind = PortKind.${kind.port}, $base)"
            is Kind.Struct ->
                    "$portClass(kind = PortKind.STRUCTURE, identifier = Optional.present(${kind.qualified}.identifier), $base)"
            is Kind.ListOf -> {
                // A list has one child, keyed "..." as the other SDKs key it.
                val child = port(portClass, "...", kind.item, kind.item.isMarkedNullable, null, at)
                "$portClass(kind = PortKind.LIST, children = Optional.present(listOf($child)), $base)"
            }
        }
    }

    /** The expression that reads a value of [type] from the JSON element [element]. */
    private fun decode(type: KSType, element: String, at: KSNode): String =
            when (val kind = kind(type, at)) {
                is Kind.Scalar -> "live.arkitekt.Wire.${kind.reader}($element)"
                is Kind.Struct -> "live.arkitekt.Wire.expand(${kind.qualified}, context, $element)"
                is Kind.ListOf -> {
                    val item = kind.item
                    val inner = decode(item.makeNotNullable(), "item", at)
                    if (item.isMarkedNullable)
                            "live.arkitekt.Wire.list($element) { item -> if (item is kotlinx.serialization.json.JsonNull) null else $inner }"
                    else "live.arkitekt.Wire.list($element) { item -> $inner }"
                }
            }

    /** The expression that writes [value] of [type] as a JSON element. */
    private fun encode(type: KSType, value: String, at: KSNode): String =
            when (val kind = kind(type, at)) {
                is Kind.Scalar -> "live.arkitekt.Wire.json($value)"
                is Kind.Struct -> "live.arkitekt.Wire.shrink(${kind.qualified}, $value)"
                is Kind.ListOf -> {
                    val item = kind.item
                    val inner = encode(item.makeNotNullable(), "item", at)
                    if (item.isMarkedNullable)
                            "kotlinx.serialization.json.JsonArray($value.map { item -> if (item == null) kotlinx.serialization.json.JsonNull else $inner })"
                    else "kotlinx.serialization.json.JsonArray($value.map { item -> $inner })"
                }
            }

    private fun KSType.className(at: KSNode): String {
        if ((declaration as? KSClassDeclaration)?.classKind == ClassKind.ANNOTATION_CLASS) {
            throw Refused("an annotation cannot be injected", at)
        }
        return declaration.qualifiedName?.asString() ?: throw Refused("a type without a name", at)
    }

    private fun optional(text: String?): String =
            if (text.isNullOrBlank()) "Optional.absent()" else "Optional.present(${text.literal()})"
}

// ---- Annotations and names ------------------------------------------------------------------

private fun KSAnnotated.annotation(qualified: String) =
        annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == qualified
        }

private fun KSValueParameter.has(qualified: String) = annotation(qualified) != null

private fun com.google.devtools.ksp.symbol.KSAnnotation.string(name: String): String =
        arguments.firstOrNull { it.name?.asString() == name }?.value as? String ?: ""

/** `showLens` → `show_lens`. */
internal fun String.snakeCase(): String =
        replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

/** `showLens` → `Show Lens`. */
internal fun String.titleCase(): String =
        replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ')
                .split(' ').filter { it.isNotEmpty() }
                .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

/** The text as a Kotlin string literal. */
internal fun String.literal(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t") + "\""

// ---- KDoc -------------------------------------------------------------------------------

/**
 * What a function's KDoc says of its action.
 *
 * With more than one paragraph, the first is the [name] and the rest the [description];
 * a single paragraph is the description alone. `@param x …` describes port `x`, `@return …`
 * the result. Line breaks inside a paragraph are joined: a description is one flowing text.
 */
internal class Doc(
        val name: String?,
        val description: String?,
        val params: Map<String, String>,
        val returns: String?
) {
    companion object {
        fun parse(raw: String?): Doc {
            if (raw.isNullOrBlank()) return Doc(null, null, emptyMap(), null)
            val lines = raw.trim().lines().map { it.trim() }

            // The tags start at the first line beginning with '@'.
            val firstTag = lines.indexOfFirst { it.startsWith("@") }.let { if (it < 0) lines.size else it }
            val paragraphs =
                    lines.take(firstTag).joinToString("\n").split(Regex("\n\\s*\n"))
                            .map { it.lines().joinToString(" ") { line -> line.trim() }.trim() }
                            .filter { it.isNotEmpty() }

            val params = LinkedHashMap<String, String>()
            var returns: String? = null
            var current: Pair<String, StringBuilder>? = null
            fun close() {
                val (tag, text) = current ?: return
                val value = text.toString().trim()
                if (tag == "@return") returns = value.ifEmpty { null }
                else if (value.isNotEmpty()) params[tag] = value
                current = null
            }
            for (line in lines.drop(firstTag)) {
                if (line.startsWith("@")) {
                    close()
                    val parts = line.split(Regex("\\s+"), limit = 3)
                    when (parts[0]) {
                        "@param" -> if (parts.size >= 2) current = parts[1] to StringBuilder(parts.getOrElse(2) { "" })
                        "@return", "@returns" ->
                                current = "@return" to StringBuilder(line.substringAfter(parts[0]).trim())
                        else -> current = null // a tag that says nothing of the action
                    }
                } else {
                    current?.second?.append(' ')?.append(line)
                }
            }
            close()

            return when {
                paragraphs.isEmpty() -> Doc(null, null, params, returns)
                paragraphs.size == 1 -> Doc(null, paragraphs[0], params, returns)
                else -> Doc(paragraphs[0], paragraphs.drop(1).joinToString("\n\n"), params, returns)
            }
        }
    }
}
