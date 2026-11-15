package live.arkitekt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An agent against a real rekuest and takt: agent protocol 2 as the server speaks it, which the
 * unit tests' stand-in for the gateway can only imitate.
 *
 * An app with two @Action functions is registered and served; tasks are assigned the way a
 * person's client assigns them (the GraphQL `assign` mutation) and read back from the server.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentIntegrationTest {

    private var stack: RekuestStack? = null
    private var serving: Job? = null
    private lateinit var agentId: String

    @BeforeAll
    fun start() {
        assumeTrue(RekuestStack.available(), "Docker is not available: the integration test needs it")
        val started = RekuestStack.start()
        stack = started

        val alias = localAlias(started.port)
        val tokens = fixedTokenManager(RekuestStack.TOKEN)
        val app = App("kotlin-integration", "0.1.0").actions(AddAction, SlowEchoAction)
        val agent = Agent(alias, tokens, app, Context(Unlok(alias, tokens), Rekuest(alias, tokens), tokens))

        runBlocking {
            agent.createAgent(app.identifier).getOrThrow()
            agent.registerFunctions()
        }
        serving = CoroutineScope(Dispatchers.Default).launch { agent.provideForever() }

        // The agent this token is, and the moment the server sees it connected.
        agentId =
                graphql("""mutation { ensureAgent(input: {name: "kotlin-integration"}) { id } }""")
                        .jsonObject["ensureAgent"]!!.jsonObject["id"]!!.jsonPrimitive.content
        await("the agent to be connected") {
            graphql("""query(${'$'}id: ID!) { agent(id: ${'$'}id) { connected } }""", buildJsonObject { put("id", agentId) })
                    .jsonObject["agent"]!!.jsonObject["connected"]!!.jsonPrimitive.boolean
        }
    }

    @AfterAll
    fun stop() {
        serving?.cancel()
        stack?.stop()
    }

    @Test
    fun `an assigned task is run and its result reaches the server`() {
        val task = assign("add", buildJsonObject { put("a", 2); put("b", 3) })
        val done = finished(task)
        assertEquals("COMPLETED", done["latestEventKind"]!!.jsonPrimitive.content)
        assertEquals(5, done["latestReturns"]!!.jsonObject["return0"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a task survives its connection, runs once and is reported once`() {
        val before = slowEchoRuns.get()
        val task = assign("slow_echo", buildJsonObject { put("text", "still here") })
        await("the task to start") { kinds(task).contains("STARTED") }

        // The server asks the agent to reconnect while the task is running. The task goes on,
        // and what it reports waits in the journal for the next connection.
        graphql("""mutation(${'$'}id: ID!) { bounce(input: {agent: ${'$'}id}) { id } }""", buildJsonObject { put("id", agentId) })

        val done = finished(task)
        assertEquals("COMPLETED", done["latestEventKind"]!!.jsonPrimitive.content, "events: ${kinds(task)}")
        assertEquals("still here", done["latestReturns"]!!.jsonObject["return0"]!!.jsonPrimitive.content)
        assertEquals(1, slowEchoRuns.get() - before, "the function ran once")
        val reported = kinds(task)
        assertEquals(1, reported.count { it == "STARTED" }, "events: $reported")
        assertEquals(1, reported.count { it == "COMPLETED" }, "events: $reported")
    }

    // ---- The server, as a person's client would talk to it -------------------------------

    private fun assign(action: String, args: JsonObject): String =
            graphql(
                    """mutation(${'$'}input: AssignInput!) { assign(input: ${'$'}input) { id } }""",
                    buildJsonObject {
                        put("input", buildJsonObject {
                            put("agent", agentId)
                            put("interface", action)
                            put("args", args)
                            put("capture", false)
                        })
                    }
            ).jsonObject["assign"]!!.jsonObject["id"]!!.jsonPrimitive.content

    private fun task(id: String): JsonObject =
            graphql(
                    """query(${'$'}id: ID!) { task(id: ${'$'}id) { isDone latestEventKind latestReturns events { kind } } }""",
                    buildJsonObject { put("id", id) }
            ).jsonObject["task"]!!.jsonObject

    private fun kinds(id: String): List<String> =
            task(id)["events"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }

    private fun finished(id: String): JsonObject {
        await("task $id to finish") { task(id)["isDone"]!!.jsonPrimitive.boolean }
        return task(id)
    }

    private fun await(what: String, seconds: Int = 60, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (true) {
            if (kotlin.runCatching(condition).getOrDefault(false)) return
            if (System.currentTimeMillis() > deadline) {
                stack?.taktLog()
                throw AssertionError("Waited $seconds s for $what")
            }
            Thread.sleep(300)
        }
    }

    private fun graphql(query: String, variables: JsonObject = JsonObject(emptyMap())): JsonElement {
        val connection = URL("http://127.0.0.1:${stack!!.port}/graphql").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer ${RekuestStack.TOKEN}")
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val stream = if (connection.responseCode < 400) connection.inputStream else connection.errorStream
        val answer = Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
        val errors = answer["errors"]
        check(errors == null || errors is JsonNull) { "GraphQL refused: $errors" }
        return answer["data"]!!
    }
}
