package live.arkitekt

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

// The actions the integration test's agent serves: @Action functions, compiled with the
// processor (kspIntegrationTest), so what is registered and run is its real output.

/** Add two numbers. */
@Action
fun add(a: Int, b: Int): Int = a + b

/** How often [slowEcho] has started: it must run once, whatever the connection does. */
val slowEchoRuns = AtomicInteger(0)

/** Say something back, after a while: long enough for a connection to drop in between. */
@Action
suspend fun slowEcho(text: String): String {
    slowEchoRuns.incrementAndGet()
    delay(6000)
    return text
}

/**
 * How often [bump] ran.
 *
 * @property count The runs so far
 */
@State
data class Counter(val count: Int = 0)

/** Count one up, and say where the count is. */
@Action
suspend fun bump(counter: StateMut<Counter>, task: Task): Int {
    task.progress(50, "counting")
    return counter.update { it.copy(count = it.count + 1) }.count
}

/** The app's panel: its source is all the app says of it, and the server reads the rest off it. */
const val COUNTER_PANEL = """<Blok kind="agent" context="panel">
  <Card>
    <Text>{self.Counter.count}</Text>
    <Button onClick={self.bump()}>Count</Button>
  </Card>
</Blok>"""
