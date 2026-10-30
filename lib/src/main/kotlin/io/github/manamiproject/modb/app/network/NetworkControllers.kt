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
    private val routedControllers = mutableMapOf<Pair<ConfigRegistry, String>, RotatingProxyNetworkController>()
    private val loggedDecisions = mutableSetOf<Pair<String, String>>()

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
     * Returns the rotating controller for a site.
     *
     * A site listed under `modb.app.network.routes` rotates through its own pool, so a site which can
     * be reached for free is not sent through a paid one. Every other site shares [rotating].
     * @since 1.0.0
     * @param hostname Site the traffic is for.
     * @param configRegistry Source of the proxy pool and route definitions.
     * @return The controller for this site, created once.
     */
    fun forHost(hostname: String, configRegistry: ConfigRegistry = DefaultConfigRegistry.instance): RotatingProxyNetworkController {
        val route = routeFor(hostname, configRegistry) ?: return rotating(configRegistry)

        return synchronized(routedControllers) {
            routedControllers.getOrPut(configRegistry to hostname) {
                log.info { "Routing [$hostname] through its own pool of [${route.size}] entries." }
                RotatingProxyNetworkController(configRegistry = configRegistry, route = route)
            }
        }
    }

    /**
     * Reads the pool configured for a site.
     *
     * Each entry of `modb.app.network.routes` reads `hostname = entry, entry, ...`. An entry is a
     * proxy, `direct` for leaving without one, or `pool` for every proxy of `modb.app.network.proxies`
     * in its configured order, which lets a free route fall back to the paid pool.
     * @since 1.0.0
     * @param hostname Site to look up.
     * @param configRegistry Source of the route definitions.
     * @return The pool for this site or `null` if it has no route of its own.
     */
    fun routeFor(hostname: String, configRegistry: ConfigRegistry = DefaultConfigRegistry.instance): List<String>? {
        val routes = configRegistry.list<String>("$CONFIG_NAMESPACE.$ROUTES") ?: return null
        val definition = routes.firstOrNull { it.substringBefore("=").trim().equals(hostname, ignoreCase = true) } ?: return null
        require(definition.contains("=")) { "Route [$definition] must read [hostname = entry, entry, ...]." }

        val pool = configRegistry.list<String>("$CONFIG_NAMESPACE.$PROXIES") ?: emptyList()
        val entries = definition.substringAfter("=")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { if (it.equals(POOL, ignoreCase = true)) pool else listOf(it) }

        require(entries.isNotEmpty()) { "Route for [$hostname] names no entries." }
        return entries
    }

    /**
     * @since 1.0.0
     * @param configRegistry Source of the proxy pool definition.
     * @param hostname Site the controller is for, so a site with a route of its own rotates through it.
     * @return A controller which can actually change the outgoing address here, or one which admits
     * it cannot.
     */
    fun forDeployment(configRegistry: ConfigRegistry = DefaultConfigRegistry.instance, hostname: String? = null): NetworkController {
        val rotating = hostname?.let { forHost(it, configRegistry) } ?: rotating(configRegistry)

        return when {
            rotating.hasProxies() -> rotating
            canRestartNetworkDevice() -> {
                logOncePerSite(hostname, "Using the network device to change the outgoing address.")
                LinuxNetworkController.instance
            }
            else -> {
                logOncePerSite(hostname, "No proxy pool configured and no network device to restart, so the outgoing address cannot be changed on demand.")
                FixedNetworkController
            }
        }
    }

    // Several classes of the same crawler ask for a controller, which would repeat the same message for each of them.
    private fun logOncePerSite(hostname: String?, message: String) {
        val site = hostname ?: "all sites"
        val isNew = synchronized(loggedDecisions) { loggedDecisions.add(site to message) }

        if (isNew) {
            log.info { "[$site] $message" }
        }
    }

    /**
     * Tells in advance whether [forDeployment] will hand out [LinuxNetworkController] for any of the given sites,
     * which is the only case in which a sudo password is needed.
     * @since 20.0.0
     * @param hostnames Sites whose crawlers change the outgoing address on demand.
     * @param configRegistry Source of the proxy pool definition.
     * @return `true` if at least one of the sites has no proxies and the network device can be restarted.
     */
    fun usesNetworkDevice(hostnames: Collection<String>, configRegistry: ConfigRegistry = DefaultConfigRegistry.instance): Boolean {
        val withoutProxies = hostnames.filterNot { forHost(it, configRegistry).hasProxies() }
        return withoutProxies.isNotEmpty() && canRestartNetworkDevice()
    }

    // The original design changes address by restarting the device, which only works where ifconfig
    // exists and the connection hands out a new address afterwards. That is a property of the
    // machine, so ask it rather than assume either way.
    private const val CONFIG_NAMESPACE = RotatingProxyNetworkController.CONFIG_NAMESPACE
    private const val ROUTES = "routes"
    private const val PROXIES = "proxies"
    private const val POOL = "pool"

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
