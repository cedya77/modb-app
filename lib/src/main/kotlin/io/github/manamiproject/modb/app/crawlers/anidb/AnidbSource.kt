package io.github.manamiproject.modb.app.crawlers.anidb

import io.github.manamiproject.modb.anidb.AnidbAnimeConverter
import io.github.manamiproject.modb.anidb.AnidbApiAnimeConverter
import io.github.manamiproject.modb.anidb.AnidbApiDownloader
import io.github.manamiproject.modb.anidb.AnidbConfig
import io.github.manamiproject.modb.anidb.AnidbDownloader
import io.github.manamiproject.modb.anidb.AnidbWebsiteConfig
import io.github.manamiproject.modb.app.network.ClearanceHttpClient
import io.github.manamiproject.modb.app.network.CloudflareClearance
import io.github.manamiproject.modb.app.network.ProxiedHttpClients
import io.github.manamiproject.modb.app.network.NetworkControllers
import io.github.manamiproject.modb.app.network.SuspendableHttpClient
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.config.StringPropertyDelegate
import io.github.manamiproject.modb.core.converter.AnimeConverter
import io.github.manamiproject.modb.core.downloader.Downloader
import io.github.manamiproject.modb.core.httpclient.HttpClient
import java.net.InetSocketAddress

/**
 * Decides where anidb entries are read from.
 *
 * The API is the access path AniDB sanctions, but it is granted per client and can be withdrawn.
 * The website carries the same data and is reachable with a browser check solved, which is slower
 * to set up and outside what the API terms describe. Which one is in use is a deployment decision,
 * so it lives in configuration rather than in the crawler.
 *
 * Everything downstream, the file suffix, the converter and the entries the conversion service
 * watches for, follows from this one choice, so it must be made in a single place.
 * @since 1.0.0
 */
object AnidbSource {

    private val source: String by StringPropertyDelegate(
        configRegistry = DefaultConfigRegistry.instance,
        namespace = CONFIG_NAMESPACE,
        default = API,
    )

    /**
     * @since 1.0.0
     * @return `true` if entries are read from the website rather than the API.
     */
    fun isWebsite(): Boolean = source.equals(WEBSITE, ignoreCase = true)

    /**
     * @since 1.0.0
     * @return Configuration matching the chosen source. Anime links are identical either way.
     */
    fun config(): MetaDataProviderConfig = when {
        isWebsite() -> AnidbWebsiteConfig
        else -> AnidbConfig
    }

    /**
     * @since 1.0.0
     * @return Converter which understands what the chosen source returns.
     */
    fun converter(): AnimeConverter = when {
        isWebsite() -> AnidbAnimeConverter.instance
        else -> AnidbApiAnimeConverter.instance
    }

    /**
     * @since 1.0.0
     * @return Client able to reach the chosen source.
     */
    fun httpClient(configRegistry: ConfigRegistry = DefaultConfigRegistry.instance): HttpClient = when {
        isWebsite() -> {
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
            )
        }
        else -> SuspendableHttpClient()
    }

    /**
     * @since 1.0.0
     * @param httpClient Client to download with.
     * @return Downloader for the chosen source.
     */
    fun downloader(httpClient: HttpClient): Downloader = when {
        isWebsite() -> AnidbDownloader(metaDataProviderConfig = config(), httpClient = httpClient)
        else -> AnidbApiDownloader(metaDataProviderConfig = config(), httpClient = httpClient)
    }

    private const val API = "api"
    private const val WEBSITE = "website"

    /**
     * Prefix of the properties read by this class.
     * @since 1.0.0
     */
    const val CONFIG_NAMESPACE: String = "modb.anidb"
}
