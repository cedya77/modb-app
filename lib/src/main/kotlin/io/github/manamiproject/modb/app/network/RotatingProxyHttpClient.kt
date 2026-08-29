package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.IntPropertyDelegate
import io.github.manamiproject.modb.core.httpclient.DefaultHttpClient
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.HttpResponse
import io.github.manamiproject.modb.core.httpclient.RequestBody
import io.github.manamiproject.modb.core.httpclient.RetryCase
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import java.net.ConnectException
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
 * A route which will not carry a connection is changed rather than reported. Retrying it is what the
 * delegate already did, and one refusing relay is otherwise enough to end a crawl which the other
 * thirty-nine could have finished.
 * @property networkController Decides which proxy is currently active.
 * @property httpClientFactory Creates the delegate for a proxy. Uses [DefaultHttpClient] by default.
 * @property maxConnectionChanges How many routes to try before giving the failure to the caller.
 */
class RotatingProxyHttpClient(
    private val networkController: RotatingProxyNetworkController = RotatingProxyNetworkController.instance,
    private val httpClientFactory: (Proxy) -> HttpClient = { DefaultHttpClient(proxy = it) },
    configRegistry: ConfigRegistry = DefaultConfigRegistry.instance,
): HttpClient {

    private val maxConnectionChanges: Int by IntPropertyDelegate(
        configRegistry = configRegistry,
        namespace = CONFIG_NAMESPACE,
        default = DEFAULT_MAX_CONNECTION_CHANGES,
    )

    private val delegates = ConcurrentHashMap<Proxy, HttpClient>()
    private val retryCases = mutableListOf<RetryCase>()

    override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse {
        return throughUsableRoute { it.get(url, headers) }
    }

    override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse {
        return throughUsableRoute { it.post(url, requestBody, headers) }
    }

    private suspend fun throughUsableRoute(request: suspend (HttpClient) -> HttpResponse): HttpResponse {
        var connectionChanges = 0

        while (true) {
            val proxy = networkController.currentProxy()

            try {
                return request.invoke(delegateFor(proxy))
            } catch (e: Throwable) {
                if (!isRouteRefusingConnections(e) || connectionChanges >= maxConnectionChanges) {
                    throw e
                }

                connectionChanges++
                log.info { "Route [$proxy] would not carry a connection, changing it [$connectionChanges/$maxConnectionChanges]." }
                networkController.restartAsync().await()
            }
        }
    }

    // Only a connection which was never established says anything about the route. A failure later in
    // the exchange belongs to the site and is the caller's to interpret.
    private fun isRouteRefusingConnections(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is ConnectException) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    override fun addRetryCases(vararg retryCases: RetryCase): HttpClient {
        this.retryCases.addAll(retryCases)
        delegates.values.forEach { it.addRetryCases(*retryCases) }
        return this
    }

    private fun delegateFor(proxy: Proxy): HttpClient = delegates.computeIfAbsent(proxy) {
        httpClientFactory.invoke(it).apply {
            if (retryCases.isNotEmpty()) {
                addRetryCases(*retryCases.toTypedArray())
            }
        }
    }

    companion object {
        private val log by LoggerDelegate()

        private const val DEFAULT_MAX_CONNECTION_CHANGES = 5

        /**
         * Prefix of the properties read by this class.
         * @since 1.0.0
         */
        const val CONFIG_NAMESPACE: String = "modb.app.network"
    }
}
