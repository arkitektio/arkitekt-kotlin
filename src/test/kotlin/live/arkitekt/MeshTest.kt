package live.arkitekt

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The mesh pieces that need no node: the platform, meshd's lines, mesh aliases, the tunnel. */
class MeshTest {

    @Test
    fun `the platform is named as the release names it`() {
        assertEquals("linux-amd64", Meshd.platform("Linux", "amd64"))
        assertEquals("linux-arm64", Meshd.platform("Linux", "aarch64"))
        assertEquals("darwin-arm64", Meshd.platform("Mac OS X", "aarch64"))
        assertEquals("darwin-amd64", Meshd.platform("Mac OS X", "x86_64"))
        assertEquals("windows-amd64", Meshd.platform("Windows 11", "amd64"))
        assertEquals("windows-arm64", Meshd.platform("Windows 11", "aarch64"))
        assertNull(Meshd.platform("FreeBSD", "amd64"))
        assertNull(Meshd.platform("Linux", "riscv64"))
    }

    @Test
    fun `meshd's lines are read, and the proxy's credentials kept out of its name`() {
        val ready = parseMeshdEvent(
                """{"event":"ready","proxy":"http://mesh:s3cr%2Ft@127.0.0.1:41234","hostname":"app-1","ips":["100.64.0.7"]}"""
        )!!
        assertEquals("ready", ready.event)
        val proxy = MeshProxy(ready.proxy!!)
        assertEquals("127.0.0.1", proxy.host)
        assertEquals(41234, proxy.port)
        assertEquals("mesh", proxy.username)
        assertEquals("s3cr/t", proxy.password)
        assertEquals("http://127.0.0.1:41234", proxy.toString())

        val error = parseMeshdEvent("""{"event":"error","code":"needs_login","message":"m"}""")!!
        assertEquals("needs_login", error.code)
        assertNull(parseMeshdEvent("not json"))
    }

    @Test
    fun `an alias is on the mesh by its kind, or by a tailnet address without one`() {
        assertTrue(Alias(host = "meshhub.1.mesh.internal", ssl = false, kind = "mesh").isMesh())
        assertFalse(Alias(host = "100.64.0.1", ssl = false, kind = "absolute").isMesh())
        assertTrue(Alias(host = "100.100.3.4", ssl = false).isMesh())
        assertFalse(Alias(host = "100.128.0.1", ssl = false).isMesh())
        assertFalse(Alias(host = "example.com", ssl = true).isMesh())
    }

    @Test
    fun `the bundled meshd is copied out, and without a key or state it asks for a login`() {
        if (Meshd.platform() == null) return
        val root = java.nio.file.Files.createTempDirectory("meshd-test").toFile()
        try {
            val binary = Meshd.binary(root)
            assertTrue(binary.canExecute())
            // A second call finds the copy and checks it, rather than writing it again.
            assertEquals(binary.lastModified(), Meshd.binary(root).lastModified())

            val error = assertFailsWith<MeshError> {
                runBlocking {
                    // meshd's own release smoke test: a coordination server, but no key to join it with.
                    MeshNode.start(binary, File(root, "node-native"), "meshtest", controlUrl = "http://127.0.0.1:1", authKey = null, timeoutSeconds = 10)
                }
            }
            assertEquals("needs_login", error.code)

            // Without a key and never joined, the route does not start meshd at all.
            val route = MeshRoute(MeshOptions(), File(root, "unjoined-native"), "meshtest", claim = null)
            assertNull(runBlocking { route.proxy() })
            assertEquals("needs_login", route.error?.code)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a mesh alias is reached through a CONNECT tunnel, with the proxy's credentials`() {
        // A proxy that takes one CONNECT, checks it, and then answers the tunnelled request
        // itself, as the far end would.
        val server = ServerSocket(0)
        var connectLine: String? = null
        var authorization: String? = null
        var tunnelled: String? = null
        val proxyThread = thread {
            server.accept().use { socket: Socket ->
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                connectLine = input.readLine()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Proxy-Authorization:")) authorization = line.substringAfter(':').trim()
                }
                val out = socket.getOutputStream()
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                out.flush()
                tunnelled = input.readLine()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                }
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                out.flush()
            }
        }

        val proxy = MeshProxy("http://mesh:token@127.0.0.1:${server.localPort}")
        val alias = Alias(host = "meshhub.1.mesh.internal", port = 8080, ssl = false, kind = "mesh", mesh = proxy)
        val body = alias.httpClient().newCall(Request.Builder().url(alias.to_http_path("ht")).build()).execute().use {
            it.body!!.string()
        }
        proxyThread.join(5000)
        server.close()

        assertEquals("ok", body)
        assertEquals("CONNECT meshhub.1.mesh.internal:8080 HTTP/1.1", connectLine)
        assertEquals(proxy.authorization, authorization)
        // Inside the tunnel the request is origin-form, as to the host itself.
        assertEquals("GET /ht HTTP/1.1", tunnelled)
    }
}
