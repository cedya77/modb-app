package io.github.manamiproject.modb.anidb

import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_NETWORK
import io.github.manamiproject.modb.core.downloader.Downloader
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.core.extensions.neitherNullNorBlank
import io.github.manamiproject.modb.core.httpclient.DefaultHttpClient
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import kotlinx.coroutines.withContext

/**
 * Downloads anime data from anidb.net
 * In case the anime addition is pending, the [download] function will return a static [String] (see [ANIDB_PENDING_FILE_INDICATOR]).
 * In case the anime is 18+ restricted, it returns [ANIDB_RESTRICTED_INDICATOR] instead.
 * The caller must handle this behavior.
 * @since 1.0.0
 * @property metaDataProviderConfig Configuration for downloading data.
 * @property httpClient To actually download the anime data.
 */
public class AnidbDownloader(
    private val metaDataProviderConfig: MetaDataProviderConfig = AnidbWebsiteConfig,
    private val httpClient: HttpClient = DefaultHttpClient(isTestContext = metaDataProviderConfig.isTestContext()),
) : Downloader {

    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String = withContext(LIMITED_NETWORK) {
        log.debug { "Downloading [anidbId=$id]" }

        val response = httpClient.get(metaDataProviderConfig.buildDataDownloadLink(id).toURL())
        val responseBody = response.bodyAsString()

        check(responseBody.neitherNullNorBlank()) { "Response body was blank for [anidbId=$id] with response code [${response.code}]" }

        val responseChecker = AnidbResponseChecker(responseBody).apply {
            checkIfCrawlerIsDetected()
        }

        check(response.isOk() || response.code == 404) { "Unexpected response code [anidbId=$id], [responseCode=${response.code}]" }

        return@withContext when {
            // 18+ pages show no data without an account: skipped, neither dead nor pending
            responseChecker.isHentai() -> {
                log.info { "Skipping [anidbId=$id], because it is 18+ restricted." }
                ANIDB_RESTRICTED_INDICATOR
            }
            responseChecker.isRemovedFromAnidb() -> {
                log.info { "Adding [anidbId=$id] to dead-entries list" }
                onDeadEntry.invoke(id)
                EMPTY
            }
            responseChecker.isAdditionPending() -> ANIDB_PENDING_FILE_INDICATOR
            else -> responseBody
        }
    }

    public companion object {
        private val log by LoggerDelegate()

        /**
         * Singleton of [AnidbDownloader]
         * @since 1.0.0
         */
        public val instance: AnidbDownloader by lazy { AnidbDownloader() }

        /**
         * Returned for an entry whose addition is still pending. There is nothing to convert.
         * @since 7.0.0
         */
        public const val ANIDB_PENDING_FILE_INDICATOR: String = ">pending<"

        /**
         * Returned for an entry which is 18+ restricted. The page shows no data without an account.
         * @since 7.0.0
         */
        public const val ANIDB_RESTRICTED_INDICATOR: String = ">restricted<"
    }
}