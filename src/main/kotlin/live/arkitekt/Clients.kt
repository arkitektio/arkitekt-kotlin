package live.arkitekt

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import com.apollographql.apollo.network.okHttpClient
import com.apollographql.apollo.network.http.HttpInterceptor
import com.apollographql.apollo.network.http.HttpInterceptorChain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import live.arkitekt.lok.graphql.MeQuery
import java.util.logging.Logger

/** Logs the GraphQL errors of every operation, and passes the response on untouched. */
class ErrorLoggingInterceptor : ApolloInterceptor {

    private val logger = Logger.getLogger("ApolloGraphQLErrorLogger")

    override fun <D : Operation.Data> intercept(
            request: ApolloRequest<D>,
            chain: ApolloInterceptorChain
    ): Flow<ApolloResponse<D>> {
        return chain.proceed(request).map { response ->
            val errors = response.errors
            if (!errors.isNullOrEmpty()) {
                logger.warning("GraphQL errors in operation ${request.operation.name()}:")
                errors.forEach { error -> logger.warning(" - ${error.message}") }
            }
            response
        }
    }
}

/** Says which service a request was for when it fails on the wire. */
class LogInterceptor(val service_name: String) : HttpInterceptor {
    override suspend fun intercept(request: HttpRequest, chain: HttpInterceptorChain): HttpResponse {
        try {
            return chain.proceed(request)
        } catch (e: Exception) {
            println("$service_name: Error during request execution: ${e.message}")
            println("$service_name: Request URL: ${request.url}")
            println("$service_name: Request method: ${request.method}")
            throw e
        }
    }
}

/**
 * An authenticated GraphQL client for a service at [alias]: every request carries the login's
 * current access token, and one refused as expired is tried again with a fresh one. What a
 * service's own client (mikro's, say) is built on.
 */
fun graphqlClient(alias: Alias, tokens: TokenManager, name: String): ApolloClient =
        ApolloClient.Builder()
                .serverUrl(alias.to_http_path("graphql"))
                .reaching(alias)
                .addHttpInterceptor(AuthorizationInterceptor { tokens.accessToken() })
                .addHttpInterceptor(LogInterceptor(name))
                .addInterceptor(AuthRetryInterceptor(tokens))
                .addInterceptor(ErrorLoggingInterceptor())
                .build()

/**
 * Reach [alias] the way it was resolved: through the mesh node's proxy when it is a mesh alias
 * (see Mesh.kt), with Apollo's own HTTP client otherwise. Every Apollo client of a service
 * should be built with it.
 */
fun ApolloClient.Builder.reaching(alias: Alias): ApolloClient.Builder =
        if (alias.mesh != null) okHttpClient(alias.httpClient()) else this

/** Lok, the deployment itself: who is logged in. */
class Unlok(alias: Alias, tokens: TokenManager) {
    private val apolloClient: ApolloClient = graphqlClient(alias, tokens, "unlok")

    fun getClient(): ApolloClient = apolloClient

    suspend fun getUser(): MeQuery.Data =
            getClient().query(MeQuery()).execute().data
                    ?: throw Exception("Failed to retrieve user data")

    companion object {
        /**
         * What an app that asks lok who is logged in ([getUser]) needs there. Every app is
         * handed this client, but only one that uses it asks for the scope:
         * `App(…).scopes(Unlok.SCOPES)`.
         */
        val SCOPES: List<String> = listOf("lok_read")
    }
}

/** Rekuest: where an app's actions are registered and assigned. */
class Rekuest(alias: Alias, tokens: TokenManager) {
    private val apolloClient: ApolloClient = graphqlClient(alias, tokens, "rekuest")

    fun getClient(): ApolloClient = apolloClient
}
