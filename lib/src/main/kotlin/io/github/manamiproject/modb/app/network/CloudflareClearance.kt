package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.StringPropertyDelegate
import io.github.manamiproject.modb.core.httpclient.DefaultHttpClient
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.RequestBody
import io.github.manamiproject.modb.core.json.Json
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.net.URL

/**
 * Holds a clearance for a site which answers automated requests with a browser check.
 *
 * Obtaining one costs a full browser render, so it is done once and the resulting cookie and user
 * agent are reused for subsequent requests. A clearance is tied to the address it was issued to, so
 * the solver has to reach the site the same way the crawler does.
 * @since 1.0.0
 * @property configRegistry Source of the solver endpoint.
 * @property httpClient Used to talk to the solver, not to the site.
 */
class CloudflareClearance(
    configRegistry: ConfigRegistry = DefaultConfigRegistry.instance,
    private val httpClient: HttpClient = DefaultHttpClient(),
) {

    private val solverUrl: String by StringPropertyDelegate(
        configRegistry = configRegistry,
        namespace = CONFIG_NAMESPACE,
        default = EMPTY_SOLVER,
    )

    private val lock = Mutex()

    /**
     * Cookie header value of the current clearance, empty when none has been obtained.
     * @since 1.0.0
     */
    var cookie: String = ""
        private set

    /**
     * User agent the clearance was issued to. A clearance presented by a different agent is refused.
     * @since 1.0.0
     */
    var userAgent: String = ""
        private set

    /**
     * @since 1.0.0
     * @return `true` if a solver has been configured.
     */
    fun isConfigured(): Boolean = solverUrl != EMPTY_SOLVER && solverUrl.isNotBlank()

    /**
     * Obtains a clearance by having the solver render the given page.
     * @since 1.0.0
     * @param url Page to render. Any page of the site will do.
     * @return `true` if a clearance was obtained.
     */
    suspend fun refresh(url: URL): Boolean = lock.withLock {
        check(isConfigured()) { "No challenge solver configured. Set [$CONFIG_NAMESPACE.solverUrl]." }

        log.info { "Requesting a clearance for [${url.host}] from the challenge solver." }

        val body = RequestBody(
            mediaType = "application/json",
            body = """{"cmd":"request.get","url":"$url","maxTimeout":$SOLVER_TIMEOUT}""",
        )

        val response = httpClient.post(URI(solverUrl).toURL(), body).bodyAsString()
        val parsed = Json.parseJson<SolverResponse>(response)

        if (parsed?.status != "ok" || parsed.solution == null) {
            log.warn { "Solver could not obtain a clearance: [${parsed?.message}]" }
            return@withLock false
        }

        cookie = parsed.solution.cookies.joinToString("; ") { "${it.name}=${it.value}" }
        userAgent = parsed.solution.userAgent

        log.info { "Clearance obtained for [${url.host}], carrying [${parsed.solution.cookies.size}] cookies." }

        return@withLock cookie.isNotBlank()
    }

    companion object {
        private val log by LoggerDelegate()

        private const val EMPTY_SOLVER = ""
        private const val SOLVER_TIMEOUT = 120000

        /**
         * Prefix of the properties read by this class.
         * @since 1.0.0
         */
        const val CONFIG_NAMESPACE = "modb.app.challenge"
    }
}

private data class SolverResponse(
    val status: String,
    val message: String? = null,
    val solution: Solution? = null,
)

private data class Solution(
    val status: Int,
    val userAgent: String,
    val cookies: List<SolverCookie> = emptyList(),
)

private data class SolverCookie(
    val name: String,
    val value: String,
)
