package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.IntPropertyDelegate
import io.github.manamiproject.modb.core.config.StringPropertyDelegate
import io.github.manamiproject.modb.core.httpclient.DefaultHttpClient
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.RequestBody
import io.github.manamiproject.modb.core.json.Json
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.ConnectException
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Holds the clearances for a site which answers automated requests with a browser check.
 *
 * Obtaining one costs a full browser render, which through a metered proxy is megabytes rather than
 * the kilobytes of the page itself. A clearance is tied to the address it was issued to, so one is
 * kept per route: rotating back to a route reuses what it earned instead of rendering again.
 *
 * Clearances are written to `modb.app.challenge.clearanceFile` when it is set, so a restarted run
 * picks them up instead of rendering again for every route. One older than
 * `modb.app.challenge.clearanceMaxAgeHours` is not presented.
 * @since 1.0.0
 * @property configRegistry Source of the solver endpoint and the clearance store settings.
 * @property httpClient Used to talk to the solver, not to the site.
 * @property hostname Site the clearances are for. Names the entries in the clearance file.
 * @property clock Decides the age of a clearance.
 */
class CloudflareClearance(
    configRegistry: ConfigRegistry = DefaultConfigRegistry.instance,
    private val httpClient: HttpClient = solverClient(),
    private val hostname: String = "",
    private val clock: Clock = Clock.systemUTC(),
) {

    private val solverUrl: String by StringPropertyDelegate(
        configRegistry = configRegistry,
        namespace = CONFIG_NAMESPACE,
        default = EMPTY_SOLVER,
    )

    private val clearanceFile: String by StringPropertyDelegate(
        configRegistry = configRegistry,
        namespace = CONFIG_NAMESPACE,
        default = NO_FILE,
    )

    private val clearanceMaxAgeHours: Int by IntPropertyDelegate(
        configRegistry = configRegistry,
        namespace = CONFIG_NAMESPACE,
        default = DEFAULT_MAX_AGE_HOURS,
    )

    private val lock = Mutex()
    private var held: MutableMap<String, StoredClearance>? = null

    /**
     * @since 1.0.0
     * @return `true` if a solver has been configured.
     */
    fun isConfigured(): Boolean = solverUrl != EMPTY_SOLVER && solverUrl.isNotBlank()

    /**
     * @since 1.0.0
     * @param route Route the clearance has to be valid for, `null` for leaving directly.
     * @return The clearance held for this route, or `null` if there is none young enough to present.
     */
    suspend fun heldFor(route: String?): Clearance? = lock.withLock {
        val entry = clearances()[keyOf(route)] ?: return@withLock null

        return@withLock when {
            isExpired(entry) -> null
            else -> Clearance(cookie = entry.cookie, userAgent = entry.userAgent)
        }
    }

    /**
     * Forgets the clearance of a route, because the site no longer accepts it.
     * @since 1.0.0
     * @param route Route whose clearance was refused.
     */
    suspend fun discard(route: String?) = lock.withLock {
        if (clearances().remove(keyOf(route)) != null) {
            persist()
        }
    }

    /**
     * Obtains a clearance for a route by having the solver render the given page.
     * @since 1.0.0
     * @param url Page to render. Any page of the site will do.
     * @param route Route the solver has to leave through, `null` for leaving directly.
     * @return `true` if a clearance was obtained.
     */
    suspend fun refresh(url: URL, route: String? = null): Boolean = lock.withLock {
        check(isConfigured()) { "No challenge solver configured. Set [$CONFIG_NAMESPACE.solverUrl]." }

        log.info { "Requesting a clearance for [${url.host}] from the challenge solver, leaving through [${route ?: "this machine"}]." }

        // The clearance is issued to whichever address renders the page, so the solver has to
        // leave by the same route the crawler will use, or the cookie it returns is worthless.
        val proxy = when {
            route.isNullOrBlank() -> ""
            else -> ""","proxy":{"url":"$route"}"""
        }

        // Images and media are most of what a render downloads and play no part in the challenge.
        val body = RequestBody(
            mediaType = "application/json",
            body = """{"cmd":"request.get","url":"$url","maxTimeout":$SOLVER_TIMEOUT,"disableMedia":true$proxy}""",
        )

        val response = try {
            httpClient.post(URI(solverUrl).toURL(), body).bodyAsString()
        } catch (e: ConnectException) {
            // OkHttp's message only names an address, which reads as if the site itself were unreachable.
            // It stays a ConnectException, so the callers' handling doesn't change.
            val address = e.message?.substringAfter("Failed to connect to ", missingDelimiterValue = "")?.ifBlank { null } ?: solverUrl
            throw ConnectException("Failed to connect to the challenge solver [$address]").apply { initCause(e) }
        }
        val parsed = Json.parseJson<SolverResponse>(response)

        if (parsed?.status != "ok" || parsed.solution == null) {
            log.warn { "Solver could not obtain a clearance: [${parsed?.message}]" }
            return@withLock false
        }

        // A solve which returns no cookie means the solver never reached the site, usually because
        // it could not use the route it was given. Reporting that as success leaves the crawler
        // presenting nothing and wondering why every request is refused.
        if (parsed.solution.cookies.isEmpty()) {
            log.warn { "Solver returned no cookies, so [${url.host}] was not reached through [${route ?: "this machine"}]." }
            return@withLock false
        }

        val key = keyOf(route)
        clearances()[key] = StoredClearance(
            host = hostname,
            route = key,
            cookie = parsed.solution.cookies.joinToString("; ") { "${it.name}=${it.value}" },
            userAgent = parsed.solution.userAgent,
            obtainedAt = clock.instant().toString(),
        )
        persist()

        log.info { "Clearance obtained for [${url.host}], carrying [${parsed.solution.cookies.size}] cookies." }

        return@withLock true
    }

    private fun keyOf(route: String?): String = route?.ifBlank { null } ?: DIRECT_ROUTE

    private fun isExpired(entry: StoredClearance): Boolean {
        val obtainedAt = runCatching { Instant.parse(entry.obtainedAt) }.getOrNull() ?: return true
        return Duration.between(obtainedAt, clock.instant()) > Duration.ofHours(clearanceMaxAgeHours.toLong())
    }

    private suspend fun clearances(): MutableMap<String, StoredClearance> {
        held?.let { return it }

        val loaded = ClearanceStore.read(storePath())
            .filter { it.host == hostname && !isExpired(it) }
            .associateBy { it.route }
            .toMutableMap()

        if (loaded.isNotEmpty()) {
            log.info { "Reusing [${loaded.size}] stored clearances for [$hostname]." }
        }

        held = loaded
        return loaded
    }

    private suspend fun persist() {
        val path = storePath() ?: return
        ClearanceStore.replace(path, hostname, clearances().values.toList())
    }

    private fun storePath(): Path? = clearanceFile.ifBlank { null }?.let { Path(it) }

    companion object {
        private val log by LoggerDelegate()

        private const val EMPTY_SOLVER = ""
        private const val NO_FILE = ""
        private const val DIRECT_ROUTE = "direct"
        private const val DEFAULT_MAX_AGE_HOURS = 24
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
            // A solver reporting that it could not get through is describing the route it was given,
            // not a fault which might clear. Retrying holds the answer back behind a backoff while
            // the caller could have been trying another route.
            useDefaultRetryCases = false,
        )

        private const val SOLVER_GRACE_IN_SECONDS = 30L

        /**
         * Prefix of the properties read by this class.
         * @since 1.0.0
         */
        const val CONFIG_NAMESPACE = "modb.app.challenge"
    }
}

/**
 * What a request has to present to pass the browser check.
 * @since 1.0.0
 * @property cookie Cookie header value.
 * @property userAgent User agent the clearance was issued to. A clearance presented by a different agent is refused.
 */
data class Clearance(
    val cookie: String,
    val userAgent: String,
)

/**
 * The clearance file is shared by every site, so writes go through one lock and replace only the
 * writing site's entries.
 */
private object ClearanceStore {

    private val lock = Mutex()

    suspend fun read(path: Path?): List<StoredClearance> = lock.withLock { load(path) }

    suspend fun replace(path: Path, host: String, entries: List<StoredClearance>) = lock.withLock {
        val others = load(path).filter { it.host != host }
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        temporary.writeText(Json.toJson(ClearanceFile(others + entries)))
        Files.move(temporary, path, REPLACE_EXISTING, ATOMIC_MOVE)
    }

    private suspend fun load(path: Path?): List<StoredClearance> {
        if (path == null || !path.exists()) {
            return emptyList()
        }

        return runCatching { Json.parseJson<ClearanceFile>(path.readText())?.clearances }.getOrNull() ?: emptyList()
    }
}

private data class ClearanceFile(
    val clearances: List<StoredClearance> = emptyList(),
)

private data class StoredClearance(
    val host: String,
    val route: String,
    val cookie: String,
    val userAgent: String,
    val obtainedAt: String,
)

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
