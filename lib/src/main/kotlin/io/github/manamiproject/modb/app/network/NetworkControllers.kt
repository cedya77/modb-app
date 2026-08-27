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

    private val rotatingControllers = mutableMapOf<ConfigRegistry, RotatingProxyNetworkController>()

    /**
     * Returns the one rotating controller belonging to a configuration.
     *
     * Which proxy of the pool is currently in use is state. Handing out a fresh controller per
     * caller gives each of them a private idea of that, so the part which rotates on refusal and the
     * part which actually sends the request disagree about where traffic leaves from: the rotation
     * is logged, nothing about the connection changes, and the address stays burned.
     * @since 1.0.0
     * @param configRegistry Source of the proxy pool definition.
     * @return The controller for this configuration, created once.
     */
    fun rotating(configRegistry: ConfigRegistry = DefaultConfigRegistry.instance): RotatingProxyNetworkController = synchronized(rotatingControllers) {
        rotatingControllers.getOrPut(configRegistry) {
            RotatingProxyNetworkController(configRegistry = configRegistry)
        }
    }

    /**
     * @since 1.0.0
     * @param configRegistry Source of the proxy pool definition.
     * @return A controller which can actually change the outgoing address here, or one which admits
     * it cannot.
     */
    fun forDeployment(configRegistry: ConfigRegistry = DefaultConfigRegistry.instance): NetworkController {
        val rotating = rotating(configRegistry)

        return when {
            rotating.hasProxies() -> rotating
            canRestartNetworkDevice() -> {
                log.info { "Using the network device to change the outgoing address." }
                LinuxNetworkController.instance
            }
            else -> {
                log.info { "No proxy pool configured and no network device to restart, so the outgoing address cannot be changed on demand." }
                FixedNetworkController
            }
        }
    }

    // The original design changes address by restarting the device, which only works where ifconfig
    // exists and the connection hands out a new address afterwards. That is a property of the
    // machine, so ask it rather than assume either way.
    private fun canRestartNetworkDevice(): Boolean = try {
        ProcessBuilder("ifconfig").redirectErrorStream(true).start().waitFor() == 0
    } catch (e: Exception) {
        false
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
