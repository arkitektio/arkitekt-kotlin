package live.arkitekt

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import live.arkitekt.rekuest.graphql.type.PortKind
import kotlin.test.Test
import kotlin.test.assertEquals

// What the processor makes of @Action functions. The functions below are compiled with it
// (kspTest), so `LabelSampleAction` and `PingAction` are its real output, not a fixture.

/** A structure that is only its id. */
class Sample(val id: String) {
    companion object : IdStructure<Sample>("@test/sample", ::Sample, Sample::id)
}

/** Something the app provides, for an injected parameter. */
class Printer(val prefix: String)

/**
 * Label Sample
 *
 * Give a sample a label,
 * once for every tag.
 *
 * @param sample The sample to label
 * @param tags What to call it
 * @param copies How many labels
 * @return The labels that were printed
 */
@Action
@Returns("labels")
suspend fun labelSample(
        sample: Sample,
        tags: List<String>,
        note: String?,
        copies: Int = 2,
        @Inject printer: Printer
): List<String> = tags.map { "${printer.prefix}${sample.id}:$it x$copies${note ?: ""}" }

/** Answer, to show the agent is there. */
@Action(key = "are_you_there")
fun ping(): Sample = Sample("pong")

class DeclaredActionTest {

    private fun context(): Context {
        val alias = localAlias(1)
        val tokens = fixedTokenManager()
        return Context(Unlok(alias, tokens), Rekuest(alias, tokens), tokens).also {
            it.provided += Printer("#")
        }
    }

    @Test
    fun `the definition is read off the function and its KDoc`() {
        val definition = App("test", "1.2.3").action(LabelSampleAction).actions["label_sample"]!!.definition

        assertEquals("1.2.3", definition.version)
        assertEquals("Label Sample", definition.name)
        assertEquals("Give a sample a label, once for every tag.", definition.description.getOrNull())

        val args = definition.args.getOrNull()!!
        // The injected printer is not a port.
        assertEquals(listOf("sample", "tags", "note", "copies"), args.map { it.key })
        assertEquals(
                listOf(PortKind.STRUCTURE, PortKind.LIST, PortKind.STRING, PortKind.INT),
                args.map { it.kind }
        )
        assertEquals("@test/sample", args[0].identifier.getOrNull())
        assertEquals("The sample to label", args[0].description.getOrNull())
        assertEquals(PortKind.STRING, args[1].children.getOrNull()!!.single().kind)
        // A nullable type and a default value are both nullable ports.
        assertEquals(listOf(false, false, true, true), args.map { it.nullable.getOrNull() })

        val returned = definition.returns.getOrNull()!!.single()
        assertEquals("labels", returned.key)
        assertEquals("The labels that were printed", returned.description.getOrNull())
    }

    @Test
    fun `the handler reads the arguments, calls the function and writes its result`() = runBlocking {
        val handler = LabelSampleAction.declare("1").handler
        val shrunk = buildJsonObject {
            put("__identifier", "@test/sample")
            put("object", "s-1")
        }
        val args = mapOf<String, JsonElement?>(
                "sample" to shrunk,
                "tags" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        )

        // Nothing sent for `copies`: the function's own default (2) applies.
        assertEquals(
                mapOf("labels" to JsonArray(listOf(JsonPrimitive("#s-1:a x2"), JsonPrimitive("#s-1:b x2")))),
                handler(context(), args)
        )
        // Sent: it is used, and so is the nullable note.
        assertEquals(
                JsonArray(listOf(JsonPrimitive("#s-1:a x5!"), JsonPrimitive("#s-1:b x5!"))),
                handler(context(), args + mapOf("copies" to JsonPrimitive(5), "note" to JsonPrimitive("!")))["labels"]
        )
    }

    @Test
    fun `a structure is returned under its identifier`() = runBlocking {
        val action = PingAction.declare("1")
        assertEquals("are_you_there", action.key)
        assertEquals("Answer, to show the agent is there.", action.definition.description.getOrNull())

        val result = action.handler(context(), emptyMap())["return0"]!!.jsonObject
        assertEquals("@test/sample", result["__identifier"]!!.jsonPrimitive.content)
        assertEquals("pong", result["object"]!!.jsonPrimitive.content)
    }
}
