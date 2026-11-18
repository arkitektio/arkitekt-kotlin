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
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.Modifier

class StateProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
            StateProcessor(environment.codeGenerator, environment.logger)
}

/**
 * Writes, for every top-level class marked `@State`, an `object <Name>State :
 * StateDeclaration<Name>` in the class's package: the state's name, its locks, a port per
 * property of the primary constructor, and how a value is written as JSON.
 */
class StateProcessor(private val generator: CodeGenerator, private val logger: KSPLogger) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val (ready, later) = resolver.getSymbolsWithAnnotation(STATE).partition { it.validateProperties() }
        for (symbol in ready) {
            val declared = symbol as? KSClassDeclaration ?: continue
            try {
                write(declared)
            } catch (e: Refused) {
                logger.error("@State ${declared.simpleName.asString()}: ${e.message}", e.at)
            }
        }
        return later
    }

    /** A class whose property types do not all resolve yet waits for the next round. */
    private fun KSAnnotated.validateProperties(): Boolean {
        val declared = this as? KSClassDeclaration ?: return true
        return declared.primaryConstructor?.parameters?.all { !it.type.resolve().isError } != false
    }

    private fun write(declared: KSClassDeclaration) {
        if (declared.classKind != ClassKind.CLASS) {
            throw Refused("only a class can be a state (a data class, so an update is a copy)", declared)
        }
        if (declared.parentDeclaration != null) {
            throw Refused("only a top-level class can be a state", declared)
        }
        if (Modifier.PRIVATE in declared.modifiers) {
            throw Refused("a private class cannot be read from its generated declaration", declared)
        }
        if (declared.typeParameters.isNotEmpty()) {
            throw Refused("a generic class cannot be a state", declared)
        }
        val constructor =
                declared.primaryConstructor ?: throw Refused("a state needs a primary constructor", declared)

        val annotation = declared.annotation(STATE)!!
        val className = declared.simpleName.asString()
        val qualified = declared.qualifiedName?.asString() ?: throw Refused("a class without a name", declared)
        val name = annotation.string("name").ifEmpty { className }
        val locks =
                (annotation.arguments.firstOrNull { it.name?.asString() == "locks" }?.value as? List<*>)
                        ?.filterIsInstance<String>()
                        ?: emptyList()

        // What the class says of its fields: `@property x …` (or `@param x …`) in its KDoc, or
        // the KDoc on the property itself.
        val doc = Doc.parse(declared.docString)
        val own = declared.getAllProperties().associate { it.simpleName.asString() to Doc.parse(it.docString).description }

        val fields = mutableListOf<String>()
        val ports = mutableListOf<String>()
        val entries = mutableListOf<String>()
        for (parameter in constructor.parameters) {
            val property = parameter.name?.asString() ?: throw Refused("a parameter without a name", parameter)
            if (!parameter.isVal && !parameter.isVar) {
                throw Refused("'$property' is not a property: a state's fields are the val's of its constructor", parameter)
            }
            val plan = plan(parameter.type.resolve(), emptyList(), PROVIDES, fields, parameter)
            refuseStructures(plan, property, parameter)
            ports += port("ReturnPortInput", property, plan, plan.nullable, doc.params[property] ?: own[property])
            val written = encode(plan, "it", property)
            entries +=
                    if (plan.nullable) "${property.literal()} to (value.$property?.let { $written } ?: kotlinx.serialization.json.JsonNull)"
                    else "${property.literal()} to value.$property.let { $written }"
        }
        if (ports.isEmpty()) throw Refused("a state without fields says nothing", declared)

        val objectName = className + "State"
        val packageName = declared.packageName.asString()
        val file = declared.containingFile
        val source = buildString {
            appendLine("// Written by arkitekt-kotlin's processor from the @State class `$className`.")
            appendLine("// Do not edit: change the class, its KDoc or its annotation instead.")
            if (packageName.isNotEmpty()) appendLine("package $packageName")
            appendLine()
            appendLine("import com.apollographql.apollo.api.Optional")
            appendLine("import live.arkitekt.rekuest.graphql.type.PortKind")
            appendLine("import live.arkitekt.rekuest.graphql.type.ReturnPortInput")
            appendLine()
            appendLine("/** The state `$name`, made of [$className]. Declare it with `App.state($objectName, …)`. */")
            appendLine("object $objectName : live.arkitekt.StateDeclaration<$qualified> {")
            fields.forEach { appendLine("    $it") }
            if (fields.isNotEmpty()) appendLine()
            appendLine("    override val name: String = ${name.literal()}")
            appendLine("    override val locks: List<String> = listOf(${locks.joinToString(", ") { it.literal() }})")
            appendLine("    override val ports: List<ReturnPortInput> =")
            appendLine("            listOf(")
            ports.forEach { appendLine("                $it,") }
            appendLine("            )")
            appendLine()
            appendLine("    override fun snapshot(value: $qualified): kotlinx.serialization.json.JsonObject =")
            appendLine("            kotlinx.serialization.json.JsonObject(")
            appendLine("                mapOf(")
            entries.forEach { appendLine("                    $it,") }
            appendLine("                )")
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

    /** A structure in a state would have to be shrunk to be published: not here yet. */
    private fun refuseStructures(plan: Plan, property: String, at: KSNode) {
        when (val kind = plan.kind) {
            is Kind.Struct ->
                    throw Refused(
                            "'$property' is a structure (${kind.qualified}): a state's fields are String, Int, " +
                                    "Long, Float, Double, Boolean and Lists of those",
                            at
                    )
            is Kind.ListOf -> refuseStructures(kind.item, property, at)
            is Kind.Scalar -> Unit
        }
    }
}
