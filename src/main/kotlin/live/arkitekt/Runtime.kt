package live.arkitekt

import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest

/**
 * Runs an [App]: logs it in to a deployment, builds its [Context] and hands back a [Connection]
 * that serves its actions. The app says what it is; everything that touches a server is here.
 *
 *     val connection = Runtime(app).connect("https://go.arkitekt.live")
 *     connection.serve()   // until the server or the caller ends it
 *
 * @param cacheFile where the granted login is kept between runs.
 * @param approve shown the page a person approves the app at, the first time (and whenever the
 *   kept login no longer works). By default it is opened in the system browser.
 */
class Runtime(
        val app: App,
        cacheFile: File = File(System.getProperty("user.home"), ".arkitekt/fakts_cache.json"),
        private val approve: (String) -> Unit = ::openInBrowser
) {
    private val fakts = FaktsClient(OkHttpClient())

    // The app's manifest: what the deployment is shown at login. The lok/management endpoint
    // arrives as ActiveFakts.self.alias, so it is not a requirement of any app.
    private val manifest =
            Manifest(
                    identifier = app.identifier,
                    version = app.version,
                    scopes = app.scopes.toList(),
                    description = app.description,
                    node_id = NodeId.getOrSet(),
                    requirements = app.requirements.toList()
            )

    // The services that must be granted and reachable for the app to function.
    private val requiredKeys = app.requirements.filter { !it.optional }.map { it.key }

    private val cache = FaktsCache(cacheFile)

    // The "v2:" prefix binds the key to the protocol generation, so a protocol-1 record can
    // never be mistaken for a protocol-2 one even if the manifest and url are unchanged.
    private fun cacheHash(url: String): String {
        val raw = "v2:" + faktsJson.encodeToString(manifest) + "|" + url
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * True if [connect] to [url] can proceed silently, without a person approving in a browser.
     *
     * Under fakts protocol 2 that is not "do I have a config" but "do I hold a refresh token":
     * the config alone is inert, because there are no client credentials to mint a token from.
     */
    fun hasCachedLogin(url: String): Boolean =
            cache.load(cacheHash(url))?.token?.refresh_token != null

    /** Forget the kept login: the next [connect] asks a person again. */
    fun forget() = cache.clear()

    private suspend fun negotiate(url: String): FaktsSession {
        val endpoint = fakts.discover(url)
        val authorization = fakts.deviceAuthorize(endpoint, manifest)

        approve(
                authorization.verification_uri_complete
                        ?: authorization.verification_uri?.replace("{code}", authorization.user_code.orEmpty())
                        ?: throw DemandError("Device authorization carried no verification URI to approve at.")
        )

        val grant =
                fakts.pollToken(
                        tokenEndpoint = authorization.token_endpoint ?: endpoint.token_endpoint,
                        deviceCode = authorization.device_code,
                        clientId = authorization.client_id,
                        interval = authorization.interval,
                        expiresIn = authorization.expires_in
                )

        return FaktsSession(cacheHash(url), endpoint, grant.fakts, grant.token)
    }

    /** The granted session and whether it came from the cache. */
    private suspend fun session(url: String, forceRefresh: Boolean = false): Pair<FaktsSession, Boolean> {
        if (!forceRefresh) {
            cache.load(cacheHash(url))?.let { return it to true }
        }
        val session = negotiate(url)
        cache.save(session)
        return session to false
    }

    /**
     * Log the app in at [url], build its services and register its actions.
     *
     * Throws when a required service is not granted or not reachable ([CompositionError]) or
     * the login is refused. Nothing is served until [Connection.serve] is called.
     */
    suspend fun connect(url: String): Connection {
        // Resolve a granted session. A refresh rejected by the server means the cached refresh
        // chain is dead (revoked, superseded, or simply too old), so wipe the cache and run the
        // device flow from scratch — but only once, to avoid an approval loop.
        var forceRefresh = false
        var session: FaktsSession
        var instances: Map<String, Alias>
        var tokens: TokenManager

        while (true) {
            val loaded = session(url, forceRefresh = forceRefresh)
            session = loaded.first
            var fromCache = loaded.second

            // Every required service must have been granted AND resolve to a reachable alias.
            var (failure, resolved) = compose(session)
            instances = resolved

            // A cached session can go stale in two ways — a requirement stops being granted, or
            // its aliases stop answering — and both are fixed the same way: re-negotiate exactly
            // once and re-run the whole check before giving up.
            if (fromCache && failure != null) {
                println("Cached session no longer composes ($failure); re-negotiating once.")
                cache.clear()
                session = session(url, forceRefresh = true).first
                fromCache = false
                val recomposed = compose(session)
                failure = recomposed.first
                instances = recomposed.second
            }

            failure?.let { throw CompositionError(it) }

            tokens = tokenManager(url, session)

            // A cached access token is very likely expired; prove the credentials still work
            // before wiring clients to them.
            try {
                tokens.accessToken()
                break
            } catch (e: TokenError) {
                if (fromCache && !forceRefresh) {
                    println(
                            "The cached refresh token was rejected (${e.statusCode ?: "transport"}); " +
                                    "clearing the cache and restarting the device flow from scratch."
                    )
                    cache.clear()
                    forceRefresh = true
                    continue
                }
                throw e
            }
        }

        val envelope = session.fakts
        val rekuestAlias = instances[App.REKUEST]!!

        // The declared services, in order: one may build on those before it.
        val built = LinkedHashMap<Service<*>, Any>()
        for (service in app.services) {
            val alias = instances[service.key] ?: continue // optional, and not there
            built[service] = service.build(ServiceScope(alias, tokens, built))
        }
        val context =
                Context(
                        unlok = Unlok(envelope.self.alias, tokens), // lok is the deployment (self.alias)
                        rekuest = Rekuest(rekuestAlias, tokens),
                        tokens = tokens,
                        services = built
                )

        // What the app provides for injection, made now that the clients exist.
        app.providers.forEach { context.provided += it(context) }

        // Tell the deployment which aliases actually answered. Best-effort; never throws.
        val reports = envelope.instances.keys.associateWith { key ->
            val alias = instances[key]
            if (alias != null) AliasReport(valid = true, alias_id = alias.id)
            else AliasReport(valid = false, reason = "No working alias found for service: $key")
        }
        fakts.report(session.endpoint, tokens.accessToken(), reports, functional = reports.values.all { it.valid })

        val agent = Agent(rekuestAlias, tokens, app, context)
        agent.createAgent(app.identifier)
        agent.registerFunctions()

        return Connection(context, agent)
    }

    /**
     * Check every required service is granted and resolve it to a reachable alias.
     *
     * Returns (failure, instances), where a null failure means composed. It reports rather than
     * throws so the caller can distinguish "this cached session went stale" — worth exactly one
     * re-negotiation — from "the deployment genuinely will not serve us".
     */
    private suspend fun compose(session: FaktsSession): Pair<String?, Map<String, Alias>> {
        val ungranted = requiredKeys.filter { key ->
            val status = GrantStatus.from(session.fakts.statuses[key])
            session.fakts.instances[key] == null ||
                    status == GrantStatus.DENIED ||
                    status == GrantStatus.UNAVAILABLE
        }
        if (ungranted.isNotEmpty()) {
            val detail = ungranted.joinToString { "$it=${GrantStatus.from(session.fakts.statuses[it])}" }
            return "Required service(s) not granted: $detail" to emptyMap()
        }

        val instances = buildInstanceMap(session.fakts)
        val unreachable = requiredKeys.filter { instances[it] == null }
        val failure =
                if (unreachable.isEmpty()) null
                else "No reachable alias for required service(s): ${unreachable.joinToString()}"
        return failure to instances
    }

    /**
     * The single token owner for this login. Every rotation is persisted back to the cache
     * before the new access token is handed out: the refresh token rotates on use, so a lost
     * one costs the user a browser round-trip on the next start.
     */
    private fun tokenManager(url: String, session: FaktsSession): TokenManager {
        val hash = cacheHash(url)
        return TokenManager(fakts, session.endpoint, session.token, session.fakts) {
            endpoint, activeFakts, token ->
            cache.save(FaktsSession(hash, endpoint, activeFakts, token))
        }
    }
}

/** A logged-in app: its [context], and the agent that serves its actions. */
class Connection(val context: Context, private val agent: Agent) {
    /**
     * Serve the app's actions, reconnecting when the socket drops. Returns when the server ends
     * it for good (a success when it was kicked, a failure otherwise); cancel it to stop.
     */
    suspend fun serve(): Result<String> = agent.provideForever()
}

/** Open [url] in the system browser, or print it when that cannot be done. */
fun openInBrowser(url: String) {
    val os = System.getProperty("os.name").lowercase()
    try {
        when {
            os.contains("win") -> ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
            os.contains("mac") -> ProcessBuilder("open", url).start()
            os.contains("nix") || os.contains("nux") -> ProcessBuilder("xdg-open", url).start()
            else -> println("Please open this URL to approve the app: $url")
        }
    } catch (e: Exception) {
        println("Could not open a browser automatically. Approve the app at: $url")
    }
    println("Waiting for approval at: $url")
}
