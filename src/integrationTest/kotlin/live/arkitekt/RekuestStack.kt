package live.arkitekt

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * A real rekuest, started in Docker for a test run and removed after it: the server, takt, the
 * gateway in front of both, database, redis and object storage (`src/integrationTest/stack`).
 * The same stack rekuest's own Python suite starts.
 */
class RekuestStack private constructor(private val directory: File, private val project: String, val port: Int, private val storagePort: Int) {

    private fun compose(vararg arguments: String, timeoutMinutes: Long = 10): Int {
        val command = listOf("docker", "compose", "-p", project, "-f", "docker-compose.yml") + arguments
        val process =
                ProcessBuilder(command)
                        .directory(directory)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                        .apply {
                            environment()["REKUEST_HOST_PORT"] = port.toString()
                            environment()["RUSTFS_HOST_PORT"] = storagePort.toString()
                        }
                        .start()
        check(process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            "docker compose ${arguments.joinToString(" ")} did not finish in $timeoutMinutes minutes"
        }
        return process.exitValue()
    }

    private fun start() {
        check(compose("up", "-d") == 0) { "The rekuest stack did not start" }
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(4)
        while (!answers()) {
            check(System.currentTimeMillis() < deadline) { "rekuest did not answer on port $port" }
            Thread.sleep(1000)
        }
    }

    private fun answers(): Boolean =
            try {
                val connection = URL("http://127.0.0.1:$port/graphql").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 2000
                connection.readTimeout = 5000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write("""{"query":"{__typename}"}""".toByteArray()) }
                connection.responseCode == 200
            } catch (e: Exception) {
                false
            }

    /** The last lines takt logged: what a failed test wants to see. */
    fun taktLog(): Int = compose("logs", "--tail", "60", "takt", timeoutMinutes = 1)

    /** Remove the stack and what it stored. */
    fun stop() {
        compose("down", "-v", "--remove-orphans", timeoutMinutes = 3)
    }

    companion object {
        /** A token the stack's configuration maps to a test user (`configs/rekuest.yaml`). */
        const val TOKEN = "test"

        /** Whether there is a Docker to start it in. */
        fun available(): Boolean =
                try {
                    ProcessBuilder("docker", "compose", "version")
                            .redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .start()
                            .waitFor() == 0
                } catch (e: Exception) {
                    false
                }

        /** Start it on two free ports. Stopped again if it does not come up. */
        fun start(): RekuestStack {
            val directory = File(System.getProperty("arkitekt.stack") ?: "src/integrationTest/stack")
            val (gateway, storage) =
                    ServerSocket(0).use { a -> ServerSocket(0).use { b -> a.localPort to b.localPort } }
            val stack = RekuestStack(directory, "arkitekt-kotlin-it-${ProcessHandle.current().pid()}", gateway, storage)
            try {
                stack.start()
            } catch (e: Throwable) {
                stack.stop()
                throw e
            }
            return stack
        }
    }
}
