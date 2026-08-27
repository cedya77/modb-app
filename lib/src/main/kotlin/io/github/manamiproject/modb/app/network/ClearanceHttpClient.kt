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
 */
class ClearanceHttpClient(
    private val clearance: CloudflareClearance,
    private val httpClient: HttpClient,
): HttpClient {

    override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse {
        if (clearance.cookie.isBlank()) {
            clearance.refresh(url)
        }

        val response = httpClient.get(url, headers + clearanceHeaders())

        if (response.code != CHALLENGED) {
            return response
        }

        log.info { "Clearance for [${url.host}] was refused, renewing it." }

        return when {
            clearance.refresh(url) -> httpClient.get(url, headers + clearanceHeaders())
            else -> response
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
    }
}
