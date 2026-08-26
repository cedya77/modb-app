package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.core.httpclient.DefaultHttpClient
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.HttpResponse
import io.github.manamiproject.modb.core.httpclient.RequestBody
import io.github.manamiproject.modb.core.httpclient.RetryCase
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * [HttpClient] which sends every request through the proxy that [RotatingProxyNetworkController]
 * currently considers active.
 *
 * One delegate is kept per proxy so that connection pools survive a switch and are reused when the
 * rotation comes back around. Retry cases registered here are forwarded to every delegate, including
 * the ones created after registration.
 * @since 1.0.0
 * @property networkController Decides which proxy is currently active.
 * @property httpClientFactory Creates the delegate for a proxy. Uses [DefaultHttpClient] by default.
 */
class RotatingProxyHttpClient(
    private val networkController: RotatingProxyNetworkController = RotatingProxyNetworkController.instance,
    private val httpClientFactory: (Proxy) -> HttpClient = { DefaultHttpClient(proxy = it) },
): HttpClient {

    private val delegates = ConcurrentHashMap<Proxy, HttpClient>()
    private val retryCases = mutableListOf<RetryCase>()

    override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse {
        return currentDelegate().get(url, headers)
    }

    override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse {
        return currentDelegate().post(url, requestBody, headers)
    }

    override fun addRetryCases(vararg retryCases: RetryCase): HttpClient {
        this.retryCases.addAll(retryCases)
        delegates.values.forEach { it.addRetryCases(*retryCases) }
        return this
    }

    private fun currentDelegate(): HttpClient = delegates.computeIfAbsent(networkController.currentProxy()) { proxy ->
        httpClientFactory.invoke(proxy).apply {
            if (retryCases.isNotEmpty()) {
                addRetryCases(*retryCases.toTypedArray())
            }
        }
    }
}
