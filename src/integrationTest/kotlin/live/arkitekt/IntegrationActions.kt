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
