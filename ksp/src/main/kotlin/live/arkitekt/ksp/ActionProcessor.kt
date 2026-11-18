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
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier

private const val ACTION = "live.arkitekt.Action"
private const val INJECT = "live.arkitekt.Inject"
private const val RETURNS = "live.arkitekt.Returns"
private const val TASK = "live.arkitekt.Task"
private const val STATE_MUT = "live.arkitekt.StateMut"
private const val STATE_REF = "live.arkitekt.StateRef"

/** A parameter with a default value is tried in every present/absent combination: 2^n calls. */
private const val MAX_DEFAULTS = 4

class ActionProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
            ActionProcessor(environment.codeGenerator, environment.logger)
}


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

        // Constraint lists, written once as fields of the generated object: the definition and
        // the run-time checks read the same ones.
        val fields = mutableListOf<String>()

        // Parameters: a port each, unless injected.
        val ports = mutableListOf<String>()
        val fixed = mutableListOf<Pair<String, String>>() // parameter name -> expression
        val defaulted = mutableListOf<Pair<String, String>>() // parameter name -> port key
        val decoders = mutableMapOf<String, String>()
        // The states the function takes (their generated declarations), and those it changes.
        val states = mutableListOf<String>()
        val manipulated = mutableListOf<String>()
        for (parameter in function.parameters) {
            val parameterName =
                    parameter.name?.asString() ?: throw Refused("a parameter without a name", parameter)
            if (parameter.isVararg) throw Refused("a vararg cannot be a port: take a List", parameter)
            if (parameter.has(INJECT)) {
                val injected = unalias(parameter.type.resolve()).type
                fixed += parameterName to "context.inject(${injected.className(parameter)}::class.java)"
                continue
            }
            // What the running task hands over is no port either: its own handle, or a state.
            val given = unalias(parameter.type.resolve()).type
            when (val qualified = given.declaration.qualifiedName?.asString()) {
                TASK -> {
                    fixed += parameterName to "live.arkitekt.Task.current()"
                    continue
                }
                STATE_MUT, STATE_REF -> {
                    val state = stateOf(given, parameter)
                    val handle = if (qualified == STATE_MUT) "state" else "stateRef"
                    fixed += parameterName to "live.arkitekt.Task.current().$handle($state)"
                    if (state !in states) states += state
                    if (qualified == STATE_MUT && state !in manipulated) manipulated += state
                    continue
                }
            }
            val plan = plan(parameter.type.resolve(), parameter.annotations.toList(), REQUIRES, fields, parameter)
            val nullable = plan.nullable || parameter.hasDefault
            ports += port("ArgPortInput", parameterName, plan, nullable, doc.params[parameterName])
            val decode = decode(plan, "it", parameterName)
            when {
                parameter.hasDefault -> {
                    defaulted += parameterName to parameterName
                    decoders[parameterName] =
                            "live.arkitekt.Wire.required(args, ${parameterName.literal()}).let { $decode }"
                }
                plan.nullable ->
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
        val isUnit = unalias(returned).type.declaration.qualifiedName?.asString() == "kotlin.Unit"
        val returnPlan =
                if (isUnit) null
                else plan(returned, function.annotations.toList(), PROVIDES, fields, function)
        val returnPorts =
                if (returnPlan == null) emptyList()
                else listOf(port("ReturnPortInput", returnKey, returnPlan, returnPlan.nullable, returnDescription))

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
                if (returnPlan == null) "$invocation\n                    emptyMap()"
                else {
                    val encode =
                            if (returnPlan.nullable)
                                    "result?.let { ${encode(returnPlan, "it", returnKey)} } ?: kotlinx.serialization.json.JsonNull"
                            else encode(returnPlan, "result", returnKey)
                    "val result = $invocation\n                    mapOf(${returnKey.literal()} to $encode)"
                }

        // The locks it runs under: its own, and those of the states it takes.
        val ownLocks =
                (annotation.arguments.firstOrNull { it.name?.asString() == "locks" }?.value as? List<*>)
                        ?.filterIsInstance<String>()
                        ?: emptyList()
        val locks =
                if (ownLocks.isEmpty() && states.isEmpty()) null
                else (listOf("listOf(${ownLocks.joinToString(", ") { it.literal() }})") + states.map { "$it.locks" })
                        .joinToString(" + ", prefix = "(", postfix = ").distinct()")

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
            fields.forEach { appendLine("    $it") }
            if (fields.isNotEmpty()) appendLine()
            appendLine("    override fun declare(version: String): live.arkitekt.RegisteredAction =")
            appendLine("            live.arkitekt.RegisteredAction(")
            appendLine("                DefinitionInput(")
            appendLine("                    key = ${key.literal()},")
            appendLine("                    version = version,")
            appendLine("                    name = ${name.literal()},")
            appendLine("                    description = ${optional(description)},")
            appendLine("                    args = Optional.present(listOf(${ports.joinToString(", ")})),")
            appendLine("                    returns = Optional.present(listOf(${returnPorts.joinToString(", ")})),")
            appendLine("                    kind = ActionKind.FUNCTION" + if (states.isEmpty()) "" else ",")
            if (states.isNotEmpty()) appendLine("                    stateful = Optional.present(true)")
            appendLine("                ),")
            if (locks != null) appendLine("                locks = $locks,")
            if (manipulated.isNotEmpty()) {
                appendLine("                manipulates = listOf(${manipulated.joinToString(", ") { "$it.name" }}),")
            }
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

    /**
     * The generated declaration of the state a `StateMut<T>` or `StateRef<T>` parameter names:
     * `<T>State`, written by the state processor beside the `@State` class.
     */
    private fun stateOf(handle: KSType, at: KSNode): String {
        val named =
                handle.arguments.firstOrNull()?.type?.resolve()?.let { unalias(it).type }
                        ?: throw Refused("a state handle names its state: StateMut<YourState>", at)
        val declaration = named.declaration
        val qualified = declaration.qualifiedName?.asString() ?: throw Refused("a type without a name", at)
        if (declaration.annotation(STATE) == null) {
            throw Refused("$qualified is no state: mark the class @State and declare it with App.state", at)
        }
        return qualified + "State"
    }
}

// ---- Annotations and names ------------------------------------------------------------------

internal fun KSAnnotated.annotation(qualified: String) =
        annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == qualified
        }

internal fun KSValueParameter.has(qualified: String) = annotation(qualified) != null

internal fun com.google.devtools.ksp.symbol.KSAnnotation.string(name: String): String =
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
                        // `@property` is how a class's KDoc names a field: a state's port.
                        "@param", "@property" ->
                                if (parts.size >= 2) current = parts[1] to StringBuilder(parts.getOrElse(2) { "" })
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
