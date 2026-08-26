package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.anime.Seconds
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.ListPropertyDelegate
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_NETWORK
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Proxy.Type.HTTP
import java.net.Proxy.Type.SOCKS
import java.net.URI
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit.SECONDS
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * [NetworkController] which changes the outgoing IP address by switching to the next proxy of a
 * configured pool instead of restarting the network device.
 *
 * [LinuxNetworkController] relies on a SLAAC enabled IPv6 connection: restarting the device makes the
 * operating system hand out a new address. That is unavailable on a cloud instance, where a restart
 * returns the same address, so this implementation moves the exit point instead of the interface.
 *
 * Proxies are configured as a list under `modb.app.network.proxies`. Entries are either `host:port`,
 * which is treated as HTTP, or a URI carrying the scheme, for example `socks5://host:1080`.
 * @since 1.0.0
 * @property appConfig Application specific configuration. Uses [AppConfig] by default.
 * @property configRegistry Source of the proxy pool definition.
 * @property timeRangeForMaxRestarts Time range in seconds in which the number of switches defined by [maxNumberOfRestarts] is allowed.
 * @property maxNumberOfRestarts Maximum number of switches allowed within [timeRangeForMaxRestarts].
 * @property cooldown Time in seconds to wait after every proxy of the pool has been used once.
 * @throws TooManyRestartsException if the number of switches within [timeRangeForMaxRestarts] exceeds [maxNumberOfRestarts].
 */
class RotatingProxyNetworkController(
    private val appConfig: Config = AppConfig.instance,
    configRegistry: ConfigRegistry = DefaultConfigRegistry.instance,
    private val timeRangeForMaxRestarts: Seconds = 600,
    private val maxNumberOfRestarts: Int = timeRangeForMaxRestarts + timeRangeForMaxRestarts / 2,
    private val cooldown: Seconds = 60,
): NetworkController {

    private val proxies: List<String> by ListPropertyDelegate(
        configRegistry = configRegistry,
        namespace = CONFIG_NAMESPACE,
        default = emptyList(),
    )

    private val pool: List<Proxy> by lazy {
        val parsed = proxies.map { toProxy(it) }
        check(parsed.isNotEmpty()) { "No proxies configured. Set [$CONFIG_NAMESPACE.proxies]." }
        log.info { "Proxy pool initialized with [${parsed.size}] entries." }
        parsed
    }

    private val writeLock = Mutex()
    private val restarts = mutableListOf<LocalDateTime>()
    private var currentIndex = 0
    private var isNetworkActive = true

    /**
     * The proxy that requests are currently supposed to go through.
     * @since 1.0.0
     * @return Proxy of the pool which is currently active.
     */
    fun currentProxy(): Proxy = pool[currentIndex]

    override fun isNetworkActive(): Boolean = isNetworkActive

    override suspend fun restartAsync(): Deferred<Boolean> = withContext(LIMITED_NETWORK) {
        return@withContext writeLock.withLock {
            if (!isRestartRequestValid()) {
                log.info { "Ignoring request to switch proxy, because a switch is already in progress." }
                return@withContext async { false }
            }

            isNetworkActive = false

            async {
                val previous = pool[currentIndex]
                currentIndex = (currentIndex + 1) % pool.size

                if (currentIndex == 0) {
                    log.info { "Every proxy of the pool has been used. Waiting [$cooldown] seconds before reusing the first one." }
                    delay(cooldown.toDuration(DurationUnit.SECONDS))
                }

                log.info { "Switching outgoing connection from [${previous.address()}] to [${pool[currentIndex].address()}]." }

                isNetworkActive = true
                return@async true
            }
        }
    }

    private fun isRestartRequestValid(): Boolean {
        val now = LocalDateTime.now(appConfig.clock())

        if (restarts.isEmpty()) {
            restarts.add(now)
            return true
        }

        val differenceInSeconds = SECONDS.between(restarts.first(), now)

        if (restarts.size < maxNumberOfRestarts && differenceInSeconds < timeRangeForMaxRestarts) {
            restarts.add(now)
            return true
        }

        if (differenceInSeconds > timeRangeForMaxRestarts) {
            restarts.clear()
            restarts.add(now)
            return true
        }

        throw TooManyRestartsException(maxNumberOfRestarts, timeRangeForMaxRestarts)
    }

    private fun toProxy(definition: String): Proxy {
        val uri = when {
            definition.contains("://") -> URI(definition)
            else -> URI("http://$definition")
        }

        val type = when (uri.scheme.lowercase()) {
            "http", "https" -> HTTP
            "socks", "socks4", "socks5" -> SOCKS
            else -> throw IllegalArgumentException("Unsupported proxy scheme [${uri.scheme}] in [$definition].")
        }

        require(!uri.host.isNullOrBlank() && uri.port != -1) { "Proxy definition [$definition] must contain host and port." }

        return Proxy(type, InetSocketAddress(uri.host, uri.port))
    }

    companion object {
        private val log by LoggerDelegate()

        /**
         * Prefix of the properties read by this class.
         * @since 1.0.0
         */
        const val CONFIG_NAMESPACE = "modb.app.network"

        /**
         * Singleton of [RotatingProxyNetworkController].
         * @since 1.0.0
         */
        val instance: RotatingProxyNetworkController by lazy { RotatingProxyNetworkController() }
    }
}
