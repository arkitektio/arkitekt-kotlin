package live.arkitekt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

// =========================================================================================
// The deployment's mesh, as Python's fakts reaches it: a node of our own (arkitekt-meshd, run
// as a child process) and the local HTTP proxy it serves. A mesh alias is challenged and used
// through that proxy; nothing else about a client changes.
//
//   - The login asks for a mesh key (`request_auth_key`); the first token carries it once
//     (`mesh`), and it is kept across refreshes.
//   - A service's other aliases are tried first. Only when none answers is the node started
//     (once, for the whole login) and the service's mesh aliases challenged through it.
//   - The node's state is kept per app identity, so it joins with the key once and starts
//     without one after that. It stops with the Connection, or when this JVM does: meshd
//     exits when its stdin closes.
// =========================================================================================

/** A failure to reach the mesh. [code] is meshd's (`needs_login`, `timeout`, `locked`, ...). */
class MeshError(message: String, val code: String? = null) : FaktsError(message)

/**
 * Whether and how an app uses the mesh. [Mode.AUTO] (the default) asks for a mesh key when
 * arkitekt-meshd is there to run, uses the mesh only for a service that answers no other way,
 * and stays quiet when it cannot; [Mode.FORCE] reaches every service over the mesh and over
 * nothing else; [Mode.OFF] never.
 *
 * [fromEnvironment] reads `ARKITEKT_MESH` (`0`/`off`, `force`), as Python's arkitekt does.
 */
data class MeshOptions(
        val mode: Mode = Mode.AUTO,
        /** Where nodes keep their state (default: `<state dir>/arkitekt/mesh`, as Python's). */
        val stateRoot: File? = null,
        /** The node's hostname (default: `<app identifier>-<device id>`). */
        val hostname: String? = null,
        /** How long joining may take. */
        val timeoutSeconds: Int = 90
) {
    enum class Mode { OFF, AUTO, FORCE }

    val forced: Boolean get() = mode == Mode.FORCE

    /** Whether a login asks the server for a key to join with. */
    fun requestsKey(): Boolean = when (mode) {
        Mode.OFF -> false
        Mode.FORCE -> true
        Mode.AUTO -> Meshd.available()
    }

    fun resolvedStateRoot(): File = stateRoot ?: File(Meshd.stateDir(), "arkitekt/mesh")

    companion object {
        fun fromEnvironment(): MeshOptions =
                when (System.getenv("ARKITEKT_MESH")?.trim()?.lowercase()) {
                    "0", "off", "false", "no" -> MeshOptions(mode = Mode.OFF)
                    "force" -> MeshOptions(mode = Mode.FORCE)
                    else -> MeshOptions()
                }
    }
}

/** The mesh key a login was granted: sent once, with the first token. */
@Serializable
data class MeshClaim(val ionscale_auth_key: String, val ionscale_coord_url: String? = null)

// ---- The proxy ----------------------------------------------------------------------------

/**
 * The mesh node's local HTTP proxy: `http://mesh:TOKEN@127.0.0.1:PORT`. Never log [url]: it
 * carries the credentials every request must present.
 */
class MeshProxy(val url: String) {
    val host: String
    val port: Int
    val username: String?
    val password: String?

    init {
        val uri = URI(url)
        host = uri.host ?: throw MeshError("The mesh proxy url has no host")
        port = if (uri.port > 0) uri.port else 80
        val info = uri.rawUserInfo
        if (info != null) {
            val colon = info.indexOf(':')
            username = URLDecoder.decode(if (colon < 0) info else info.substring(0, colon), "UTF-8")
            password = if (colon < 0) "" else URLDecoder.decode(info.substring(colon + 1), "UTF-8")
        } else {
            username = null
            password = null
        }
    }

    /** The `Proxy-Authorization` value, if the proxy asks for credentials. */
    val authorization: String?
        get() = username?.let {
            "Basic " + Base64.getEncoder().encodeToString("$it:${password.orEmpty()}".toByteArray(Charsets.UTF_8))
        }

    /** Where the proxy listens, without the credentials: what a log may show. */
    override fun toString(): String = "http://$host:$port"

    /**
     * [base], with every connection opened as a `CONNECT` tunnel through this proxy: plain
     * http, TLS and websockets alike (the proxy only upgrades a tunnel, never an absolute-form
     * request). Mesh hostnames are not resolved here; the proxy dials them on the mesh.
     */
    fun okHttp(base: OkHttpClient = MESH_HTTP): OkHttpClient =
            base.newBuilder()
                    .proxy(Proxy.NO_PROXY)
                    .socketFactory(TunnelSocketFactory(this))
                    .dns(UnresolvedDns)
                    .build()
}

/**
 * The base of every mesh client: a peer the node has not spoken to yet costs a handshake (or a
 * relay) before the first byte, so the timeouts are Apollo's rather than OkHttp's.
 */
val MESH_HTTP: OkHttpClient =
        OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

/** Hands the name on, unresolved: the tunnel names its target by host, as the proxy wants it. */
internal object UnresolvedDns : Dns {
    private val nowhere = byteArrayOf(0, 0, 0, 0)
    override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByAddress(hostname, nowhere))
}

internal class TunnelSocketFactory(private val proxy: MeshProxy) : SocketFactory() {
    override fun createSocket(): Socket = TunnelSocket(proxy)
    override fun createSocket(host: String, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }
    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            createSocket(host, port)
    override fun createSocket(host: InetAddress, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            createSocket(address, port)
}

/** A socket that, asked to connect to `host:port`, connects to the proxy and asks it for a tunnel. */
internal class TunnelSocket(private val proxy: MeshProxy) : Socket() {
    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val target = endpoint as? InetSocketAddress
                ?: throw IOException("A mesh tunnel needs a host and port, not $endpoint")
        // The name it was asked for (UnresolvedDns kept it), never a resolved address.
        val host = target.hostString
        val authority = if (':' in host) "[$host]:${target.port}" else "$host:${target.port}"

        super.connect(InetSocketAddress(proxy.host, proxy.port), timeout)
        val previous = soTimeout
        // The proxy answers once it reached the host over the mesh.
        soTimeout = if (timeout > 0) timeout else 30_000
        try {
            val request = StringBuilder()
                    .append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(authority).append("\r\n")
            proxy.authorization?.let { request.append("Proxy-Authorization: ").append(it).append("\r\n") }
            request.append("\r\n")
            val out = getOutputStream()
            out.write(request.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val head = readHead(getInputStream())
            val status = head.lineSequence().first()
            val code = status.split(' ').getOrNull(1)?.toIntOrNull()
            if (code != 200) {
                throw IOException("The mesh proxy did not reach $authority: $status")
            }
        } catch (e: IOException) {
            close()
            throw e
        }
        soTimeout = previous
    }

    /** The response head, read a byte at a time: whatever follows it belongs to the tunnel. */
    private fun readHead(input: InputStream): String {
        val head = StringBuilder()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw IOException("The mesh proxy closed the connection before it answered")
            head.append(b.toChar())
            if (head.length > 16 * 1024) throw IOException("The mesh proxy's answer has no end")
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
        return head.toString()
    }
}

// ---- The binary -----------------------------------------------------------------------------

/**
 * arkitekt-meshd for this machine. `ARKITEKT_MESHD` (or the system property `arkitekt.meshd`)
 * names one to run; otherwise the one bundled for this platform (`live.arkitekt:arkitekt-kotlin-meshd`
 * on the classpath) is copied out once per version, and checked against its hash every time.
 */
object Meshd {
    private const val RESOURCES = "/live/arkitekt/meshd"

    /** This machine as the release names it: `linux-amd64`, `darwin-arm64`, `windows-amd64`, ... */
    fun platform(
            osName: String = System.getProperty("os.name"),
            osArch: String = System.getProperty("os.arch")
    ): String? {
        val os = osName.lowercase().let {
            when {
                it.startsWith("linux") -> "linux"
                it.startsWith("mac") || it.startsWith("darwin") -> "darwin"
                it.startsWith("windows") -> "windows"
                else -> return null
            }
        }
        val arch = when (osArch.lowercase()) {
            "amd64", "x86_64", "x64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> return null
        }
        return "$os-$arch"
    }

    private fun executable(platform: String) =
            if (platform.startsWith("windows")) "arkitekt-meshd.exe" else "arkitekt-meshd"

    private fun override(): File? =
            (System.getProperty("arkitekt.meshd") ?: System.getenv("ARKITEKT_MESHD"))
                    ?.takeIf { it.isNotBlank() }?.let(::File)

    /** The bundled version, if one is on the classpath. */
    val bundledVersion: String? by lazy {
        javaClass.getResourceAsStream("$RESOURCES/VERSION")?.use { it.readBytes().toString(Charsets.UTF_8).trim() }
    }

    private val bundledHashes: Map<String, String> by lazy {
        javaClass.getResourceAsStream("$RESOURCES/SHA256SUMS")?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.associate { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                parts[1] to parts[0]
            }
        } ?: emptyMap()
    }

    /** Whether there is a meshd to run, without copying anything out. */
    fun available(): Boolean {
        override()?.let { return it.isFile }
        val platform = platform() ?: return false
        return javaClass.getResource("$RESOURCES/$platform/${executable(platform)}") != null
    }

    /**
     * The meshd to run: the override, or the bundled one for this platform, copied out under
     * [root] (the per-user state directory).
     */
    @Synchronized
    fun binary(root: File = stateDir()): File {
        override()?.let {
            if (!it.isFile) throw MeshError("ARKITEKT_MESHD names $it, which is not a file")
            return it
        }
        val platform = platform()
                ?: throw MeshError(
                        "arkitekt-meshd is not built for ${System.getProperty("os.name")} " +
                                "${System.getProperty("os.arch")}: set ARKITEKT_MESHD to one built for it"
                )
        val name = executable(platform)
        val version = bundledVersion
                ?: throw MeshError(
                        "arkitekt-meshd is not on the classpath (live.arkitekt:arkitekt-kotlin-meshd): " +
                                "add it, or set ARKITEKT_MESHD to a binary"
                )
        val expected = bundledHashes["$platform/$name"]
                ?: throw MeshError("The bundled arkitekt-meshd $version carries no hash for $platform")
        val target = File(root, "arkitekt/meshd/$version/$platform/$name")
        if (target.isFile && sha256(target) == expected) return target

        val resource = javaClass.getResourceAsStream("$RESOURCES/$platform/$name")
                ?: throw MeshError("The bundled arkitekt-meshd $version has no binary for $platform")
        target.parentFile.mkdirs()
        // Written beside, then moved: a second process starting at the same moment never runs
        // half a file.
        val part = File.createTempFile(name, ".part", target.parentFile)
        try {
            resource.use { input -> part.outputStream().use { input.copyTo(it) } }
            val actual = sha256(part)
            if (actual != expected) {
                throw MeshError("The bundled arkitekt-meshd for $platform has sha256 $actual, not $expected")
            }
            part.setExecutable(true, true)
            try {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                // Another process put it there first (and on Windows it may be running).
                if (!(target.isFile && sha256(target) == expected)) throw e
            }
        } finally {
            part.delete()
        }
        return target
    }

    /**
     * The per-user state directory, as Python's fakts and the Rust client pick it, so their
     * nodes live side by side.
     */
    fun stateDir(): File {
        val home = File(System.getProperty("user.home"))
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.startsWith("windows") -> System.getenv("LOCALAPPDATA")?.let(::File) ?: File(home, "AppData/Local")
            os.startsWith("mac") -> File(home, "Library/Application Support")
            else -> System.getenv("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".local/state")
        }
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** A DNS label: lowercase alphanumerics and dashes, at most 63 characters (as Python's). */
fun hostnameLabel(raw: String): String {
    val label = raw.lowercase().replace(Regex("[^a-z0-9]"), "-").take(63).trim('-')
    return label.ifEmpty { "arkitekt-app" }
}

// ---- The node -------------------------------------------------------------------------------

/** One line of meshd's stdout. */
@Serializable
internal data class MeshdEvent(
        val event: String,
        val proxy: String? = null,
        val hostname: String? = null,
        val ips: List<String> = emptyList(),
        val code: String? = null,
        val message: String? = null,
        val state: String? = null,
        val reason: String? = null
)

/** How the node stands with its coordination server (meshd's `control` events). */
data class MeshControl(val state: String, val reason: String? = null) {
    /** `connected`, or `reconnecting` (it mends that itself); anything else takes a new login. */
    val usable: Boolean get() = state == "connected" || state == "reconnecting"
}

/** A running arkitekt-meshd. Close it to stop it; it also stops when this JVM does. */
class MeshNode internal constructor(
        private val process: Process,
        val proxy: MeshProxy,
        val hostname: String,
        val ips: List<String>,
        /** Where meshd writes its log. */
        val log: File,
        stdout: BufferedReader
) : AutoCloseable {
    @Volatile
    var control: MeshControl = MeshControl("connected")
        private set

    init {
        val reader = Thread({
            try {
                while (true) {
                    val line = stdout.readLine() ?: break
                    val event = parseMeshdEvent(line) ?: continue
                    if (event.event == "control" && event.state != null) {
                        val next = MeshControl(event.state, event.reason)
                        if (!next.usable && control.usable) {
                            println("The mesh node is off the mesh (${next.state}): ${next.reason ?: "no reason given"}")
                        } else if (next.usable && !control.usable) {
                            println("The mesh node is back on the mesh")
                        }
                        control = next
                    }
                }
            } catch (_: IOException) {
                // The process is gone; nothing more to read.
            }
        }, "arkitekt-meshd-events")
        reader.isDaemon = true
        reader.start()
    }

    val alive: Boolean get() = process.isAlive

    /** Stop meshd: close its stdin and wait for it, then make sure. */
    override fun close() {
        try {
            process.outputStream.close()
        } catch (_: IOException) {
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
        }
    }

    override fun toString(): String = "MeshNode($hostname, proxy at $proxy)"

    companion object {
        /**
         * Whether a node already joined in [statedir] (and can start without a key): it holds
         * its identity and its coordination server, as meshd's `Session::has_state` checks.
         */
        fun hasState(statedir: File): Boolean =
                File(statedir, "identity.json").isFile && File(statedir, "control-url").isFile

        /**
         * Start [binary] in [statedir]: joining with [authKey] when it has not joined there yet,
         * starting from its state otherwise. Returns once meshd says it is ready; throws
         * [MeshError] with meshd's code when it says it is not.
         */
        suspend fun start(
                binary: File,
                statedir: File,
                hostname: String,
                controlUrl: String?,
                authKey: String?,
                timeoutSeconds: Int = 90
        ): MeshNode = withContext(Dispatchers.IO) {
            statedir.parentFile?.mkdirs()
            val log = File(statedir.parentFile, statedir.name + ".log")
            val command = mutableListOf(
                    binary.absolutePath,
                    "--statedir", statedir.absolutePath,
                    "--hostname", hostname,
                    "--timeout", "${timeoutSeconds}s",
                    // Without credentials, any user of this machine would be this node to the mesh.
                    "--proxy-auth"
            )
            if (controlUrl != null) command += listOf("--control-url", controlUrl)
            val builder = ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.to(log))
            // The key never goes on the command line, where any user could read it.
            builder.environment().remove("ARKITEKT_MESH_AUTHKEY")
            if (authKey != null) builder.environment()["ARKITEKT_MESH_AUTHKEY"] = authKey
            val process = try {
                builder.start()
            } catch (e: IOException) {
                throw MeshError("Could not run arkitekt-meshd ($binary): ${e.message}", "start")
            }
            val stdout = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            try {
                while (true) {
                    val line = stdout.readLine()
                            ?: throw MeshError(
                                    "arkitekt-meshd stopped before it was ready (exit ${process.waitFor()}); see $log",
                                    "start"
                            )
                    val event = parseMeshdEvent(line) ?: continue
                    when (event.event) {
                        "ready" -> {
                            val proxy = MeshProxy(
                                    event.proxy ?: throw MeshError("arkitekt-meshd was ready without a proxy", "start")
                            )
                            return@withContext MeshNode(process, proxy, event.hostname ?: hostname, event.ips, log, stdout)
                        }
                        "error" -> throw MeshError(meshdErrorMessage(event.code, event.message), event.code)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            } catch (e: Throwable) {
                process.destroy()
                throw e
            }
        }
    }
}

internal fun parseMeshdEvent(line: String): MeshdEvent? =
        try {
            faktsJson.decodeFromString(MeshdEvent.serializer(), line)
        } catch (_: Exception) {
            null
        }

internal fun meshdErrorMessage(code: String?, message: String?): String {
    val said = message ?: "no message"
    return when (code) {
        "needs_login" -> "This app has no mesh key yet: log in again to be granted one ($said)"
        "locked", "statedir" -> "The mesh node's state is in use or unusable, is the app running twice? ($said)"
        "timeout" -> "The mesh node did not join in time ($said)"
        "locked_out" -> "The mesh's tailnet lock does not trust this node yet ($said)"
        else -> "The mesh node could not start${code?.let { " ($it)" } ?: ""}: $said"
    }
}

// ---- The route: one node per login, started when a service needs it -----------------------

/**
 * How one [Runtime.connect] reaches mesh aliases. The node is started the first time a service
 * needs it, once; a start that failed is remembered rather than tried again for every service.
 */
class MeshRoute internal constructor(
        val options: MeshOptions,
        private val statedir: File,
        private val hostname: String,
        private val claim: MeshClaim?
) : AutoCloseable {
    private val mutex = Mutex()

    /** The node, once it started. */
    @Volatile
    var node: MeshNode? = null
        private set

    /** Why there is no node, once a start failed. */
    @Volatile
    var error: MeshError? = null
        private set

    private var startedAt: Long = 0L

    @Volatile
    private var closed = false

    val forced: Boolean get() = options.forced

    /** Whether the node came up less than [SETTLE_MS] ago: its peers may not know of it yet. */
    fun settling(): Boolean = node != null && System.nanoTime() - startedAt < SETTLE_MS * 1_000_000L

    /** The proxy into the mesh, starting the node on the first call; null when it cannot start. */
    suspend fun proxy(): MeshProxy? = mutex.withLock {
        node?.let { running ->
            return@withLock if (running.alive && running.control.usable) running.proxy else null
        }
        if (error != null || closed) return@withLock null
        if (claim == null && !MeshNode.hasState(statedir)) {
            // No key, and never joined here: meshd would only say it knows no coordination
            // server. A login that asks for a key (again) is what it takes.
            error = MeshError(meshdErrorMessage("needs_login", "no mesh key was granted, and $statedir has not joined"), "needs_login")
            if (options.mode != MeshOptions.Mode.AUTO) println("The mesh node could not start: ${error!!.message}")
            return@withLock null
        }
        try {
            // Not cancellable: meshd's start is a blocking read, and a node built after its
            // caller gave up would be dropped, running on with its state locked until the JVM
            // exits. Kept, the `closed` check below or close() stops it.
            val started = withContext(NonCancellable) {
                MeshNode.start(
                        Meshd.binary(),
                        statedir,
                        hostname,
                        controlUrl = claim?.ionscale_coord_url,
                        authKey = claim?.ionscale_auth_key,
                        timeoutSeconds = options.timeoutSeconds
                )
            }
            if (closed) {
                started.close()
                error = MeshError("The mesh node was closed while it was starting")
                return@withLock null
            }
            node = started
            startedAt = System.nanoTime()
            println("Connected to the mesh as ${started.hostname}; proxy at ${started.proxy}")
            started.proxy
        } catch (e: MeshError) {
            error = e
            if (!(options.mode == MeshOptions.Mode.AUTO && e.code == "needs_login")) {
                println("The mesh node could not start: ${e.message}")
            }
            null
        }
    }

    override fun close() {
        closed = true
        node?.close()
        node = null
    }

    companion object {
        /** Milliseconds a node that has just started may need until its peers answer. */
        const val SETTLE_MS = 10_000L
    }
}
