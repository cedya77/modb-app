package io.github.manamiproject.modb.app.crawlers.livechart

import io.github.manamiproject.modb.core.httpclient.CloudflareChallengeRetryCase
import io.github.manamiproject.modb.app.crawlers.HighestIdDetector
import io.github.manamiproject.modb.app.extensions.checkedBody
import io.github.manamiproject.modb.app.network.SuspendableHttpClient
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.coverage.KoverIgnore
import io.github.manamiproject.modb.core.excludeFromTestContext
import io.github.manamiproject.modb.core.extractor.DataExtractor
import io.github.manamiproject.modb.core.extractor.XmlDataExtractor
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import io.github.manamiproject.modb.core.random
import kotlinx.coroutines.delay
import kotlin.time.DurationUnit.MILLISECONDS
import kotlin.time.toDuration

/**
 * Detects the furthest future year for which anime releases are scheduled.
 * @since 1.0.0
 * @property metaDataProviderConfig Configuration for a specific metadata provider.
 * @property httpClient Implementation of [HttpClient] which is used to download the site containing an overview of available years.
 * @property extractor Extractor which retrieves the data from raw data.
 */
class LivechartNewestYearDetector(
    private val metaDataProviderConfig: MetaDataProviderConfig = LivechartNewestYearDetectorConfig,
    private val httpClient: HttpClient = SuspendableHttpClient().addRetryCases(CloudflareChallengeRetryCase),
    private val extractor: DataExtractor = XmlDataExtractor,
): HighestIdDetector {

    override suspend fun detectHighestId(): Int {
        log.info { "Detecting newest year for [${metaDataProviderConfig.hostname()}]." }

        wait()

        val url = metaDataProviderConfig.buildDataDownloadLink().toURL()
        val response = httpClient.get(
            url = url,
            headers = mapOf("host" to listOf("www.${metaDataProviderConfig.hostname()}")),
        ).checkedBody(this::class)

        // The season links are read instead of the headings, because they survive a change of the layout.
        val data = extractor.extract(response, mapOf(
            "links" to "//a/@href",
            "title" to "//title/text()",
        ))

        val years = when {
            data.notFound("links") -> emptyList()
            else -> data.listNotNull<String>("links").mapNotNull { SEASON_LINK.find(it.trim())?.groupValues?.get(1)?.toInt() }
        }

        if (years.isEmpty()) {
            val title = data.stringOrDefault("title").trim().ifEmpty { "none" }
            throw IllegalStateException("No list of years found on [$url], page title [$title]. Most likely a block or maintenance page, or the layout has changed.")
        }

        return years.max()
    }

    @KoverIgnore
    private suspend fun wait() {
        excludeFromTestContext(metaDataProviderConfig) {
            delay(random(2000, 3500).toDuration(MILLISECONDS))
        }
    }

    companion object {
        private val log by LoggerDelegate()

        /**
         * A season page, such as `/winter-2027` or `/winter-2026/tv`, either relative or as full link.
         */
        private val SEASON_LINK = Regex("""^(?:https?://(?:www\.)?livechart\.me)?/(?:winter|spring|summer|fall)-(\d{4})(?:/.*)?$""")

        /**
         * Singleton of [LivechartNewestYearDetector]
         * @since 1.0.0
         */
        val instance: LivechartNewestYearDetector by lazy { LivechartNewestYearDetector() }
    }
}