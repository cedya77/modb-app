package io.github.manamiproject.modb.app.crawlers.anidb

import io.github.manamiproject.modb.anidb.AnidbConfig
import io.github.manamiproject.modb.anidb.AnidbApiDownloader
import io.github.manamiproject.modb.anidb.AnidbDownloader
import io.github.manamiproject.modb.anidb.AnidbDownloader.Companion.ANIDB_PENDING_FILE_INDICATOR
import io.github.manamiproject.modb.anidb.AnidbDownloader.Companion.ANIDB_RESTRICTED_INDICATOR
import io.github.manamiproject.modb.anidb.AnidbBannedException
import io.github.manamiproject.modb.anidb.AnidbWebsiteConfig
import io.github.manamiproject.modb.anidb.CrawlerDetectedException
import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.app.crawlers.Crawler
import io.github.manamiproject.modb.app.crawlers.IdRangeSelector
import io.github.manamiproject.modb.app.crawlers.IntegerBasedIdRangeSelector
import io.github.manamiproject.modb.app.dataset.DeadEntriesAccessor
import io.github.manamiproject.modb.app.dataset.DefaultDeadEntriesAccessor
import io.github.manamiproject.modb.app.network.NetworkControllers
import io.github.manamiproject.modb.app.network.NetworkController
import io.github.manamiproject.modb.app.network.SuspendableHttpClient
import io.github.manamiproject.modb.app.network.isRouteFailure
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.IntPropertyDelegate
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.coverage.KoverIgnore
import io.github.manamiproject.modb.app.crawlers.FaultTolerantDownloader
import io.github.manamiproject.modb.core.downloader.Downloader
import io.github.manamiproject.modb.core.excludeFromTestContext
import io.github.manamiproject.modb.core.extensions.*
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.ThrowableRetryCase
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import io.github.manamiproject.modb.core.random
import kotlinx.coroutines.delay
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import kotlin.io.path.deleteIfExists
import kotlin.time.DurationUnit.MILLISECONDS
import kotlin.time.DurationUnit.SECONDS
import kotlin.time.toDuration

/**
 * Implementation of [Crawler] for `anidb.net`.
 * Uses [IntegerBasedIdRangeSelector] to determine which data to download.
 * Waits a random time between downloads and can move to another connection every few pages, both to reduce pressure
 * on the metadata provider.
 *
 * Reads `modb.app.crawler.deadEntriesToRecheck` like the other crawlers, but applies it to the pending entries. The
 * AniDB dead entries come from the title dump, so a dead id doesn't exist and asking for it again is pointless.
 * @since 1.0.0
 * @property appConfig Application specific configuration. Uses [AppConfig] by default.
 * @property metaDataProviderConfig Configuration for a specific metadata provider.
 * @property deadEntriesAccess Access to dead entries files.
 * @property idRangeSelector Delivers the IDs to download.
 * @property httpClient To actually download the anime data.
 * @property downloader Downloader for a specific metadata provider.
 * @property networkController Changes the connection requests leave through.
 * @property maxConnectionChanges Connections tried in a row before a refusal is treated as final.
 * @property minWaitInMilliseconds Shortest wait before each download.
 * @property maxWaitInMilliseconds Longest wait before each download.
 * @property pagesPerConnection Downloads made through one connection before moving to the next. `0` stays on it.
 * @property pendingEntries Entries whose addition is most likely still pending, in both modes.
 * @property restrictedEntries 18+ entries, skipped in website mode.
 * @property titleDump Source of the ids named by the title dump.
 */
class AnidbCrawler(
    private val appConfig: Config = AppConfig.instance,
    private val metaDataProviderConfig: MetaDataProviderConfig = AnidbSource.config(),
    private val deadEntriesAccess: DeadEntriesAccessor = DefaultDeadEntriesAccessor.instance,
    private val idRangeSelector: IdRangeSelector<Int> = IntegerBasedIdRangeSelector(
        metaDataProviderConfig = metaDataProviderConfig,
        highestIdDetector = AnidbTitlesDumpHighestIdDetector.instance,
        recheckDeadEntries = false,
    ),
    private val httpClient: HttpClient = AnidbSource.httpClient(),
    private val downloader: Downloader = FaultTolerantDownloader(
        downloader = AnidbSource.downloader(httpClient),
        hostname = metaDataProviderConfig.hostname(),
        // Detection is answered below by leaving through another connection. Skipping it here
        // would burn the run's remaining entries against an address anidb has already refused.
        // A ban which outlasts the downloader's own retries stops the crawler right away for the
        // same reason: every further request during a ban can extend it.
        isHandledByCaller = { it is CrawlerDetectedException || it is AnidbBannedException },
    ),
    private val networkController: NetworkController = NetworkControllers.forDeployment(hostname = metaDataProviderConfig.hostname()),
    private val pendingEntries: AnidbPendingEntries = DefaultAnidbPendingEntries.instance,
    private val restrictedEntries: AnidbRestrictedEntries = DefaultAnidbRestrictedEntries.instance,
    private val titleDump: suspend () -> Set<Int> = { AnidbTitlesDumpHighestIdDetector.instance.allIds() },
    configRegistry: ConfigRegistry = DefaultConfigRegistry.instance,
): Crawler {

    private val deadEntriesToRecheck: Int by IntPropertyDelegate(
        configRegistry = configRegistry,
        namespace = IntegerBasedIdRangeSelector.CONFIG_NAMESPACE,
        default = 0,
    )

    private var idsInTitleDump: Set<Int>? = null

    private val maxConnectionChanges: Int by IntPropertyDelegate(
        configRegistry = configRegistry,
        namespace = CONFIG_NAMESPACE,
        default = DEFAULT_MAX_CONNECTION_CHANGES,
    )

    private val routeFailureWaitInSeconds: Int by IntPropertyDelegate(
        configRegistry = configRegistry,
        namespace = CONFIG_NAMESPACE,
        default = DEFAULT_ROUTE_FAILURE_WAIT_IN_SECONDS,
    )

    private val minWaitInMilliseconds: Int by IntPropertyDelegate(
        configRegistry = configRegistry,
        namespace = CONFIG_NAMESPACE,
        default = DEFAULT_MIN_WAIT_IN_MILLISECONDS,
    )

    private val maxWaitInMilliseconds: Int by IntPropertyDelegate(
        configRegistry = configRegistry,
        namespace = CONFIG_NAMESPACE,
        default = DEFAULT_MAX_WAIT_IN_MILLISECONDS,
    )

    private val pagesPerConnection: Int by IntPropertyDelegate(
        configRegistry = configRegistry,
        namespace = CONFIG_NAMESPACE,
        default = DEFAULT_PAGES_PER_CONNECTION,
    )

    init {
        val restart = suspend { networkController.restartAsync().join() }
        httpClient.addRetryCases(ThrowableRetryCase(executeBefore = restart) { it is ConnectException})
        httpClient.addRetryCases(ThrowableRetryCase(executeBefore = restart) { it is UnknownHostException})
        httpClient.addRetryCases(ThrowableRetryCase(executeBefore = restart) { it is NoRouteToHostException})
        // The route is replaced before the retry, so there is nothing to wait out on the old one.
        httpClient.addRetryCases(
            ThrowableRetryCase(
                waitDuration = { attempt -> (routeFailureWaitInSeconds * attempt).toDuration(SECONDS) },
                executeBefore = restart,
            ) { it.isRouteFailure() }
        )
    }

    override suspend fun start() {
        log.info { "Starting crawler for [${metaDataProviderConfig.hostname()}]." }

        val idDownloadList = idRangeSelector.idDownloadList().toMutableList()

        if (idDownloadList.isNotEmpty()) {
            movePendingFilesToPendingEntries()
            skipRestrictedEntries(idDownloadList)
            skipPendingEntries(idDownloadList)

            when {
                idDownloadList.isEmpty() -> log.info { "No IDs left for [${metaDataProviderConfig.hostname()}] crawler to download." }
                else -> startDownload(idDownloadList)
            }
        }

        log.info { "Finished crawling data for [${metaDataProviderConfig.hostname()}]." }
    }

    /**
     * Earlier versions kept a pending website entry as a `.pending` file in the week's working directory.
     */
    private suspend fun movePendingFilesToPendingEntries() {
        appConfig.workingDir(metaDataProviderConfig).listRegularFiles("*.${ANIDB_PENDING_FILE_SUFFIX}").forEach { file ->
            file.fileName().remove(".${ANIDB_PENDING_FILE_SUFFIX}").toIntOrNull()?.let { pendingEntries.add(it) }
            file.deleteIfExists()
        }
    }

    private suspend fun skipRestrictedEntries(idDownloadList: MutableList<Int>) {
        if (!isWebsite() || restrictedEntries.all().isEmpty()) {
            return
        }

        restrictedEntries.removeAllNotIn(titleDumpIds())

        val restricted = restrictedEntries.all()
        idDownloadList.removeAll(restricted)

        log.info { "Skipping [${restricted.size}] 18+ entries of [${metaDataProviderConfig.hostname()}]." }
    }

    private fun isWebsite(): Boolean = metaDataProviderConfig.fileSuffix() == AnidbWebsiteConfig.fileSuffix()

    private suspend fun skipPendingEntries(idDownloadList: MutableList<Int>) {
        val pending = pendingEntries.all()

        if (pending.isEmpty()) {
            return
        }

        pendingEntries.removeAllNotIn(titleDumpIds())

        val stillPending = pendingEntries.all()
        val recheck = pendingEntries.selectForRecheck(deadEntriesToRecheck)
        idDownloadList.removeAll(stillPending - recheck)

        log.info { "Skipping [${stillPending.size - recheck.size}] pending entries of [${metaDataProviderConfig.hostname()}], rechecking [${recheck.size}]." }
    }

    private suspend fun titleDumpIds(): Set<Int> = idsInTitleDump ?: titleDump().also { idsInTitleDump = it }

    /**
     * An id which the API refuses, but the title dump names, is not dead. Its addition is most likely still pending.
     * The website only reports an entry as not served when the page says it has been removed from anidb. That is
     * final, so it always becomes a dead entry. (Pending and 18+ entries are answered with an indicator instead.)
     */
    private suspend fun handleNotServed(animeId: AnimeId) {
        val id = animeId.toIntOrNull()

        if (!isWebsite() && id != null && id in titleDumpIds()) {
            pendingEntries.add(id)
        } else {
            id?.let { pendingEntries.remove(it) }
            deadEntriesAccess.addDeadEntry(animeId, metaDataProviderConfig)
        }
    }

    private suspend fun startDownload(idDownloadList: List<Int>) = repeat(idDownloadList.size) { index ->
        val animeId = idDownloadList[index]
        wait()

        // A single address serving page after page is what anidb's anti-leech protection bans.
        if (pagesPerConnection > 0 && index > 0 && index % pagesPerConnection == 0) {
            log.info { "Moving [${metaDataProviderConfig.hostname()}] to the next connection after [$pagesPerConnection] pages." }
            networkController.restartAsync().await()
        }

        log.debug { "Downloading ${index+1}/${idDownloadList.size}: [anidbId=$animeId]" }

        val response = downloadChangingConnectionWhenRefused(animeId)

        when {
            response == ANIDB_PENDING_FILE_INDICATOR -> pendingEntries.add(animeId)
            response == ANIDB_RESTRICTED_INDICATOR -> {
                pendingEntries.remove(animeId)
                restrictedEntries.add(animeId)
            }
            response.neitherNullNorBlank() -> {
                val file = appConfig.workingDir(metaDataProviderConfig).resolve("$animeId.${metaDataProviderConfig.fileSuffix()}")
                response.writeToFile(file, true)
                deadEntriesAccess.removeDeadEntry(animeId.toString(), metaDataProviderConfig)
                pendingEntries.remove(animeId)
                restrictedEntries.remove(animeId)
            }
        }
    }

    /**
     * Downloads an entry, leaving through another connection each time anidb refuses the current one.
     *
     * A replacement address is not certain to be accepted either, so one refusal says nothing
     * conclusive and rotating once is not enough. Several addresses refused in a row is a different
     * signal: the run is being recognised by something other than where it comes from, and further
     * rotation only spends addresses, so the failure is allowed to travel.
     */
    private suspend fun downloadChangingConnectionWhenRefused(animeId: Int): String {
        var connectionChanges = 0

        while (true) {
            try {
                return downloader.download(animeId.toString()) {
                    handleNotServed(it)
                }
            } catch (e: Throwable) {
                if (e !is CrawlerDetectedException || connectionChanges >= maxConnectionChanges) {
                    throw e
                }

                connectionChanges++
                log.info { "[${metaDataProviderConfig.hostname()}] refused the current connection, changing it [$connectionChanges/$maxConnectionChanges]." }
                networkController.restartAsync().await()
            }
        }
    }

    @KoverIgnore
    private suspend fun wait() {
        excludeFromTestContext(metaDataProviderConfig) {
            val shortest = minWaitInMilliseconds.coerceAtLeast(0)
            val longest = maxWaitInMilliseconds.coerceAtLeast(shortest)
            val waitTime = if (longest > shortest) random(shortest, longest) else shortest.toLong()
            delay(waitTime.toDuration(MILLISECONDS))
        }
    }

    companion object {
        private val log by LoggerDelegate()

        private const val DEFAULT_MAX_CONNECTION_CHANGES = 5
        private const val DEFAULT_ROUTE_FAILURE_WAIT_IN_SECONDS = 5
        private const val DEFAULT_MIN_WAIT_IN_MILLISECONDS = 2000
        private const val DEFAULT_MAX_WAIT_IN_MILLISECONDS = 3000
        private const val DEFAULT_PAGES_PER_CONNECTION = 0

        /**
         * Prefix of the properties read by this class.
         * @since 1.0.0
         */
        const val CONFIG_NAMESPACE: String = "modb.anidb.crawler"

        /**
         * Singleton of [AnidbCrawler]
         * @since 1.0.0
         */
        val instance: AnidbCrawler by lazy { AnidbCrawler() }

        /**
         * Suffix of the pending files which earlier versions wrote. They are moved to the pending entries on start.
         * @since 7.0.0
         */
        const val ANIDB_PENDING_FILE_SUFFIX: String = "pending"
    }
}