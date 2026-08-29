package io.github.manamiproject.modb.app.crawlers.animeplanet

import io.github.manamiproject.modb.app.convfiles.AlreadyDownloadedIdsFinder
import io.github.manamiproject.modb.app.convfiles.DefaultAlreadyDownloadedIdsFinder
import io.github.manamiproject.modb.app.crawlers.PaginationIdRangeSelector
import io.github.manamiproject.modb.app.downloadcontrolstate.DefaultDownloadControlStateScheduler
import io.github.manamiproject.modb.app.downloadcontrolstate.DownloadControlStateScheduler
import io.github.manamiproject.modb.app.extensions.checkedBody
import io.github.manamiproject.modb.app.network.ClearanceHttpClients
import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.coverage.KoverIgnore
import io.github.manamiproject.modb.core.excludeFromTestContext
import io.github.manamiproject.modb.core.extensions.createShuffledList
import io.github.manamiproject.modb.core.extensions.remove
import io.github.manamiproject.modb.core.extractor.DataExtractor
import io.github.manamiproject.modb.core.extractor.XmlDataExtractor
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import io.github.manamiproject.modb.core.random
import kotlinx.coroutines.delay
import kotlin.time.DurationUnit.MILLISECONDS
import kotlin.time.toDuration

/**
 * Implementation of [PaginationIdRangeSelector].
 * Fetches the anime IDs from a given page and removes all IDs which are not scheduled for re-download and those
 * which have been downloaded already. If the crawler downloads the IDs which have been scheduled for re-download
 * first then the resulting list of this class will only contain completely new entries. The list is shuffled.
 * @since 1.0.0
 * @property metaDataProviderConfig Configuration for a specific metadata provider.
 * @property httpClient Implementation of [HttpClient] which is used to download the selected pages.
 * @property extractor Extractor which retrieves the data from raw data.
 * @property downloadControlStateScheduler Allows to check which anime are scheduled for re-download and which are not.
 * @property alreadyDownloadedIdsFinder Fetches all IDs which have already been downloaded.
 */
class AnimePlanetPaginationIdRangeSelector(
    private val metaDataProviderConfig: MetaDataProviderConfig = AnimePlanetPaginationIdRangeSelectorConfig,
    private val httpClient: HttpClient = ClearanceHttpClients.forHost(metaDataProviderConfig.hostname()),
    private val extractor: DataExtractor = XmlDataExtractor,
    private val downloadControlStateScheduler: DownloadControlStateScheduler = DefaultDownloadControlStateScheduler.instance,
    private val alreadyDownloadedIdsFinder: AlreadyDownloadedIdsFinder = DefaultAlreadyDownloadedIdsFinder.instance,
): PaginationIdRangeSelector<Int> {

    private val entriesNotScheduledForCurrentWeek = hashSetOf<AnimeId>()

    override suspend fun idDownloadList(page: Int): List<AnimeId> {
        log.info { "Retrieving IDs for [${metaDataProviderConfig.hostname()}] from page [$page]" }

        if (entriesNotScheduledForCurrentWeek.isEmpty()) {
            entriesNotScheduledForCurrentWeek.addAll(downloadControlStateScheduler.findEntriesNotScheduledForCurrentWeek(metaDataProviderConfig))
        }

        wait()

        val response = httpClient.get(
            url = metaDataProviderConfig.buildDataDownloadLink(page.toString()).toURL(),
            headers = mapOf("host" to listOf("www.${metaDataProviderConfig.hostname()}")),
        ).checkedBody(this::class)

        // Which of the two layouts comes back is a preference held in a cookie, and a request carries
        // whichever value the clearance was issued with rather than one this crawler chose. Reading
        // both means the page is understood either way instead of only when the cookie happens to
        // agree. Both layouts list the same 35 entries.
        val data = extractor.extract(response, mapOf(
            "entriesInCards" to "//li[@data-type='anime']/a/@href",
            "entriesInTable" to "//td[@class='tableTitle']/a/@href",
        ))

        if (data.notFound("entriesInCards") && data.notFound("entriesInTable")) {
            throw IllegalStateException("Unable to locate entries on page.")
        }

        val links = buildList {
            if (!data.notFound("entriesInCards")) {
                addAll(data.listNotNull<String>("entriesInCards"))
            }
            if (!data.notFound("entriesInTable")) {
                addAll(data.listNotNull<String>("entriesInTable"))
            }
        }

        val entriesOnThePage = links
            .filterNot { it.startsWith("/anime/years/") }
            .map { it.remove("/anime/") }
            .toHashSet()

        entriesOnThePage.removeAll(entriesNotScheduledForCurrentWeek)
        entriesOnThePage.removeAll(alreadyDownloadedIdsFinder.alreadyDownloadedIds(metaDataProviderConfig))
        return entriesOnThePage.toList().createShuffledList()
    }

    @KoverIgnore
    private suspend fun wait() {
        excludeFromTestContext(metaDataProviderConfig) {
            delay(random(1000, 1200).toDuration(MILLISECONDS))
        }
    }

    companion object {
        private val log by LoggerDelegate()

        /**
         * Singleton of [AnimePlanetPaginationIdRangeSelector]
         * @since 1.0.0
         */
        val instance: AnimePlanetPaginationIdRangeSelector by lazy { AnimePlanetPaginationIdRangeSelector() }
    }
}