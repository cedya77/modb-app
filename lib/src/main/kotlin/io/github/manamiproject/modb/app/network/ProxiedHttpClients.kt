package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.logging.LoggerDelegate

/**
 * Builds the http client a crawler should use based on whether a proxy pool has been configured.
 *
 * Some providers refuse requests coming from hosted networks outright, so the machine running the
 * pipeline decides whether traffic needs to leave through somewhere else. That is deployment
 * knowledge rather than something a crawler should hard code.
 * @since 1.0.0
 */
object ProxiedHttpClients {

    private val log by LoggerDelegate()

    /**
     * @since 1.0.0
     * @param configRegistry Source of the proxy pool definition.
     * @return A client which routes through the configured proxy pool, or a direct one when no pool
     * is configured.
     */
    fun suspendable(configRegistry: ConfigRegistry = DefaultConfigRegistry.instance): HttpClient {
        val networkController = NetworkControllers.rotating(configRegistry)

        return when {
            networkController.hasProxies() -> {
                log.info { "Routing requests through the configured proxy pool." }
                SuspendableHttpClient(
                    networkController = networkController,
                    httpClient = RotatingProxyHttpClient(networkController = networkController),
                )
            }
            else -> SuspendableHttpClient()
        }
    }
}
