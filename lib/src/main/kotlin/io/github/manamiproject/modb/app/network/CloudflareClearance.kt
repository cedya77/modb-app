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
    private val httpClient: HttpClient = solverClient(),
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
     * Route the current clearance was issued to, `null` when there is none.
     * @since 1.0.0
     */
    var issuedTo: String? = null
        private set

    /**
     * @since 1.0.0
     * @return `true` if a solver has been configured.
     */
    fun isConfigured(): Boolean = solverUrl != EMPTY_SOLVER && solverUrl.isNotBlank()

    /**
     * Makes sure a clearance is held for the given route, obtaining one only if the current
     * clearance was issued to a different one.
     *
     * A clearance is bound to the address which earned it, so changing route invalidates it just as
     * surely as time does. The site does not always answer the mismatch with a challenge, so waiting
     * for one before renewing can leave the crawler presenting a clearance no address it uses will
     * be accepted with.
     * @since 1.0.0
     * @param url Page to render if a clearance is needed.
     * @param proxyUrl Route the clearance has to be valid for.
     * @return `true` if a clearance for this route is held.
     */
    suspend fun ensureIssuedTo(url: URL, proxyUrl: String? = null): Boolean {
        lock.withLock {
            if (cookie.isNotBlank() && issuedTo == proxyUrl) {
                return true
            }
        }

        return refresh(url, proxyUrl)
    }

    /**
     * Obtains a clearance by having the solver render the given page.
     * @since 1.0.0
     * @param url Page to render. Any page of the site will do.
     * @return `true` if a clearance was obtained.
     */
    suspend fun refresh(url: URL, proxyUrl: String? = null): Boolean = lock.withLock {
        check(isConfigured()) { "No challenge solver configured. Set [$CONFIG_NAMESPACE.solverUrl]." }

        log.info { "Requesting a clearance for [${url.host}] from the challenge solver, leaving through [${proxyUrl ?: "this machine"}]." }

        // The clearance is issued to whichever address renders the page, so the solver has to
        // leave by the same route the crawler will use, or the cookie it returns is worthless.
        val proxy = when {
            proxyUrl.isNullOrBlank() -> ""
            else -> ""","proxy":{"url":"$proxyUrl"}"""
        }

        val body = RequestBody(
            mediaType = "application/json",
            body = """{"cmd":"request.get","url":"$url","maxTimeout":$SOLVER_TIMEOUT$proxy}""",
        )

        val response = httpClient.post(URI(solverUrl).toURL(), body).bodyAsString()
        val parsed = Json.parseJson<SolverResponse>(response)

        if (parsed?.status != "ok" || parsed.solution == null) {
            log.warn { "Solver could not obtain a clearance: [${parsed?.message}]" }
            issuedTo = null
            return@withLock false
        }

        // A solve which returns no cookie means the solver never reached the site, usually because
        // it could not use the route it was given. Reporting that as success leaves the crawler
        // presenting nothing and wondering why every request is refused.
        if (parsed.solution.cookies.isEmpty()) {
            log.warn { "Solver returned no cookies, so [${url.host}] was not reached through [${proxyUrl ?: "this machine"}]." }
            issuedTo = null
            return@withLock false
        }

        cookie = parsed.solution.cookies.joinToString("; ") { "${it.name}=${it.value}" }
        userAgent = parsed.solution.userAgent
        issuedTo = proxyUrl

        log.info { "Clearance obtained for [${url.host}], carrying [${parsed.solution.cookies.size}] cookies." }

        return@withLock cookie.isNotBlank()
    }

    companion object {
        private val log by LoggerDelegate()

        private const val EMPTY_SOLVER = ""
        private const val SOLVER_TIMEOUT = 120000

        /**
         * Client for talking to the solver, which is nothing like talking to a site.
         *
         * A render legitimately takes longer than an ordinary response, so the default read timeout
         * expires mid-solve. Retrying that does not recover anything: the solver is still working on
         * the first render, and each attempt starts another one, so a single slow solve becomes a
         * queue of browsers and the caller waits for all of them holding the clearance lock.
         * Waiting past the solver's own deadline and failing once is the honest outcome.
         */
        private fun solverClient(): HttpClient = DefaultHttpClient(
            readTimeoutInSeconds = SOLVER_TIMEOUT / 1000 + SOLVER_GRACE_IN_SECONDS,
        )

        private const val SOLVER_GRACE_IN_SECONDS = 30L

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
