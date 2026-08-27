package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.httpclient.HttpClient
import java.net.InetSocketAddress

/**
 * Builds the client for a site which answers automated requests with a browser check.
 *
 * A clearance belongs to a site and to the address which earned it, so the parts of a crawler which
 * talk to the same site have to present the same one. Building a client per part gives each of them
 * a clearance of its own: the browser is driven once per part instead of once per site, and a part
 * which changes connection leaves the others presenting a clearance for an address no longer in use.
 * @since 1.0.0
 */
object ClearanceHttpClients {

    private val clients = mutableMapOf<String, HttpClient>()

    /**
     * @since 1.0.0
     * @param hostname Site the client will talk to. Parts sharing a hostname share a clearance.
     * @param configRegistry Source of the solver endpoint and the proxy pool definition.
     * @return The client for this site, created once.
     */
    fun forHost(
        hostname: String,
        configRegistry: ConfigRegistry = DefaultConfigRegistry.instance,
    ): HttpClient = synchronized(clients) {
        clients.getOrPut(hostname) {
            val networkController = NetworkControllers.rotating(configRegistry)

            ClearanceHttpClient(
                clearance = CloudflareClearance(configRegistry = configRegistry),
                httpClient = ProxiedHttpClients.suspendable(configRegistry),
                currentProxy = {
                    when {
                        // Built by hand from host and port: an InetSocketAddress renders as
                        // "/host:port", which would produce an unusable "http:///host:port".
                        networkController.hasProxies() -> (networkController.currentProxy().address() as? InetSocketAddress)
                            ?.let { "http://${it.hostString}:${it.port}" }
                        else -> null
                    }
                },
                changeConnection = { networkController.restartAsync().await() },
            )
        }
    }
}
