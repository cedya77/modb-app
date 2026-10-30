package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.HttpResponse
import io.github.manamiproject.modb.core.httpclient.RequestBody
import io.github.manamiproject.modb.core.httpclient.RetryCase
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import java.net.URL

/**
 * Presents the [CloudflareClearance] held for the current route and obtains one only when the site
 * actually challenges.
 *
 * Rendering a page in a browser costs seconds and, through a metered proxy, megabytes, so it is done
 * only once a request has been refused. A route the site does not challenge never pays for a render,
 * and a route rotated back to presents the clearance it earned before.
 * @since 1.0.0
 * @property clearance Holds the cookie and user agent of each route.
 * @property httpClient Performs the actual request. Must reach the site by the same route the
 * clearance was issued to.
 * @property currentProxy Route requests currently leave by.
 * @property changeConnection Leaves by another route, for an address the site will not clear or a route which cannot
 * reach the site at all.
 * @property maxConnectionChanges Routes tried before the request is given up.
 */
class ClearanceHttpClient(
    private val clearance: CloudflareClearance,
    private val httpClient: HttpClient,
    private val currentProxy: () -> String? = { null },
    private val changeConnection: suspend () -> Unit = {},
    private val maxConnectionChanges: Int = DEFAULT_MAX_CONNECTION_CHANGES,
): HttpClient {

    override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse {
        var connectionChanges = 0
        var renewedOnThisRoute = false

        while (true) {
            val route = currentProxy()
            val held = clearance.heldFor(route)

            val response = try {
                httpClient.get(url, headers + headersOf(held))
            } catch (e: Throwable) {
                if (!e.isRouteFailure() || connectionChanges++ >= maxConnectionChanges) {
                    throw e
                }

                log.info { "Could not reach [${url.host}] through [${route ?: "this machine"}]: [${e.message}], changing connection." }
                changeConnection()
                renewedOnThisRoute = false
                continue
            }

            if (response.code != CHALLENGED) {
                return response
            }

            if (held != null) {
                clearance.discard(route)
            }

            // The first challenge on a route is answered with a render. Being challenged again right
            // after one says the address is no longer accepted rather than that a clearance aged out,
            // and a route the solver cannot get through is usually one the site drops outright.
            if (!renewedOnThisRoute) {
                renewedOnThisRoute = true

                if (clearance.refresh(url, route)) {
                    response.close()
                    continue
                }

                log.info { "No clearance for [${url.host}] through [${route ?: "this machine"}]." }
            } else {
                log.info { "[${url.host}] refused a clearance issued to [${route ?: "this machine"}]." }
            }

            if (connectionChanges++ >= maxConnectionChanges) {
                log.warn { "[${url.host}] still refuses after [$connectionChanges] connections, giving the request up." }
                return response
            }

            // A refusal which isn't returned must be closed, otherwise its connection is never released.
            response.close()
            changeConnection()
            renewedOnThisRoute = false
        }
    }

    override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse {
        return httpClient.post(url, requestBody, headers + headersOf(clearance.heldFor(currentProxy())))
    }

    override fun addRetryCases(vararg retryCases: RetryCase): HttpClient {
        httpClient.addRetryCases(*retryCases)
        return this
    }

    private fun headersOf(held: Clearance?): Map<String, Collection<String>> = when (held) {
        null -> emptyMap()
        else -> mapOf(
            "Cookie" to listOf(held.cookie),
            "User-Agent" to listOf(held.userAgent),
        )
    }

    companion object {
        private val log by LoggerDelegate()
        private const val CHALLENGED = 403
        private const val DEFAULT_MAX_CONNECTION_CHANGES = 5
    }
}
