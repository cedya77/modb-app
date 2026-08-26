package io.github.manamiproject.modb.anidb

import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.config.StringPropertyDelegate
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_NETWORK
import io.github.manamiproject.modb.core.downloader.Downloader
import io.github.manamiproject.modb.core.httpclient.DefaultHttpClient
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.zip.GZIPInputStream

/**
 * Downloads anime data from the AniDB HTTP API.
 *
 * Requires a client which has been registered with AniDB. The client name and version identify the
 * caller on every request and a ban applies to the client rather than to an IP address, so both are
 * configuration rather than constants.
 * @since 1.0.0
 * @property metaDataProviderConfig Configuration for downloading data.
 * @property configRegistry Source of the registered client name and version.
 * @property httpClient To actually download the anime data.
 */
public class AnidbApiDownloader(
    private val metaDataProviderConfig: MetaDataProviderConfig = AnidbApiConfig,
    configRegistry: ConfigRegistry = DefaultConfigRegistry.instance,
    private val httpClient: HttpClient = DefaultHttpClient(isTestContext = metaDataProviderConfig.isTestContext()),
) : Downloader {

    private val client: String by StringPropertyDelegate(
        configRegistry = configRegistry,
        namespace = CONFIG_NAMESPACE,
    )

    private val clientver: String by StringPropertyDelegate(
        configRegistry = configRegistry,
        namespace = CONFIG_NAMESPACE,
    )

    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String = withContext(LIMITED_NETWORK) {
        log.debug { "Downloading [anidbId=$id]" }

        val url = URI("${metaDataProviderConfig.buildDataDownloadLink(id)}&client=$client&clientver=$clientver&protover=$PROTOCOL_VERSION").toURL()
        val response = httpClient.get(url)
        val body = decompressIfNecessary(response.bodyAsByteArray())

        check(response.isOk()) { "Unexpected response code [anidbId=$id], [responseCode=${response.code}]" }
        check(body.isNotBlank()) { "Response body was blank for [anidbId=$id]." }

        val error = ERROR_CODE.find(body)

        if (error != null) {
            val code = error.groupValues[1]
            val message = error.groupValues[2]

            if (code in DEAD_ENTRY_CODES) {
                log.info { "Adding [anidbId=$id] to dead entries list, because the API responded with [$message]." }
                onDeadEntry.invoke(id)
                return@withContext EMPTY_RESPONSE
            }

            throw IllegalStateException("API responded with error [code=$code], [message=$message] for [anidbId=$id].")
        }

        return@withContext body
    }

    private fun decompressIfNecessary(body: ByteArray): String {
        // The API gzips responses whether or not the client asked for it, so the http client has no
        // reason to unwrap them on its own.
        val isGzipped = body.size > 1 && body[0] == GZIP_MAGIC_FIRST && body[1] == GZIP_MAGIC_SECOND

        return when {
            isGzipped -> GZIPInputStream(ByteArrayInputStream(body)).bufferedReader().use { it.readText() }
            else -> body.decodeToString()
        }
    }

    public companion object {
        private val log by LoggerDelegate()

        private const val PROTOCOL_VERSION = 1
        private const val EMPTY_RESPONSE = ""
        private const val GZIP_MAGIC_FIRST: Byte = 0x1f
        private const val GZIP_MAGIC_SECOND: Byte = 0x8b.toByte()
        private val ERROR_CODE = Regex("""<error code="(\d+)">([^<]*)</error>""")

        /**
         * Error codes which mean that the anime does not exist rather than that something went wrong.
         * @since 1.0.0
         */
        public val DEAD_ENTRY_CODES: Set<String> = setOf("330")

        /**
         * Prefix of the properties read by this class.
         * @since 1.0.0
         */
        public const val CONFIG_NAMESPACE: String = "modb.anidb"
    }
}
