package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.HttpResponse
import io.github.manamiproject.modb.core.httpclient.RequestBody
import io.github.manamiproject.modb.core.httpclient.RetryCase
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import java.net.URL

/**
 * Presents a [CloudflareClearance] on every request and renews it when the site stops accepting it.
 *
 * Rendering a page in a browser costs seconds, so doing it per request would make a crawl of any
 * size impractical. Once a clearance exists, ordinary requests carrying it are answered normally,
 * and the browser is only needed again when it expires.
 * @since 1.0.0
 * @property clearance Holds the current cookie and user agent.
 * @property httpClient Performs the actual request. Must reach the site by the same route the
 * clearance was issued to.
 * @property currentProxy Route requests currently leave by.
 * @property changeConnection Leaves by another route, for an address the site will not clear.
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

        while (true) {
            // A connection which cannot obtain a clearance at all is not one a retry will fix. It is
            // usually an address the site drops rather than challenges, and asking it again just
            // spends another render on the same dead route.
            if (!clearance.ensureIssuedTo(url, currentProxy())) {
                if (connectionChanges++ >= maxConnectionChanges) {
                    log.warn { "No clearance for [${url.host}] after [$connectionChanges] connections, giving the request up." }
                    return httpClient.get(url, headers + clearanceHeaders())
                }

                log.info { "No clearance for [${url.host}] through [${currentProxy() ?: "this machine"}], changing connection." }
                changeConnection()
                continue
            }

            val response = httpClient.get(url, headers + clearanceHeaders())

            if (response.code != CHALLENGED) {
                return response
            }

            // The clearance presented here was issued to this very connection, so being challenged
            // anyway says the address is no longer accepted rather than that the clearance aged out.
            if (connectionChanges++ >= maxConnectionChanges) {
                log.warn { "[${url.host}] still refuses a fresh clearance after [$connectionChanges] connections." }
                return response
            }

            log.info { "[${url.host}] refused a clearance issued to [${currentProxy() ?: "this machine"}], changing connection." }
            changeConnection()
        }
    }

    override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse {
        return httpClient.post(url, requestBody, headers + clearanceHeaders())
    }

    override fun addRetryCases(vararg retryCases: RetryCase): HttpClient {
        httpClient.addRetryCases(*retryCases)
        return this
    }

    private fun clearanceHeaders(): Map<String, Collection<String>> = when {
        clearance.cookie.isBlank() -> emptyMap()
        else -> mapOf(
            "Cookie" to listOf(clearance.cookie),
            "User-Agent" to listOf(clearance.userAgent),
        )
    }

    companion object {
        private val log by LoggerDelegate()
        private const val CHALLENGED = 403
        private const val DEFAULT_MAX_CONNECTION_CHANGES = 5
    }
}
