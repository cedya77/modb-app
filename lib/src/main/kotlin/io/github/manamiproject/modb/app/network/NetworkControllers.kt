package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Picks the [NetworkController] which suits the machine the pipeline runs on.
 *
 * [LinuxNetworkController] restarts a network device, which needs `ifconfig`, `sudo` and a
 * connection that hands out a new address afterwards. On a machine without those it does not fail
 * gracefully, it takes the crawler with it, which is a poor way to learn that the deployment cannot
 * change its own address.
 * @since 1.0.0
 */
object NetworkControllers {

    private val log by LoggerDelegate()

    /**
     * @since 1.0.0
     * @param configRegistry Source of the proxy pool definition.
     * @return A controller which can actually change the outgoing address here, or one which admits
     * it cannot.
     */
    fun forDeployment(configRegistry: ConfigRegistry = DefaultConfigRegistry.instance): NetworkController {
        val rotating = RotatingProxyNetworkController(configRegistry = configRegistry)

        return when {
            rotating.hasProxies() -> rotating
            else -> {
                log.info { "No proxy pool configured, so the outgoing address cannot be changed on demand." }
                FixedNetworkController
            }
        }
    }
}

/**
 * [NetworkController] for a machine whose outgoing address is not ours to change.
 *
 * Reporting failure rather than throwing lets a crawler decide what to do about a provider which
 * wants a different address, instead of ending over a capability the machine never had.
 * @since 1.0.0
 */
object FixedNetworkController: NetworkController {

    override suspend fun restartAsync(): Deferred<Boolean> = CompletableDeferred(false)

    override fun isNetworkActive(): Boolean = true
}
