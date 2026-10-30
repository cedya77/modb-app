package io.github.manamiproject.modb.anidb

import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.config.IntPropertyDelegate
import io.github.manamiproject.modb.core.config.StringPropertyDelegate
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_NETWORK
import io.github.manamiproject.modb.core.downloader.Downloader
import io.github.manamiproject.modb.core.httpclient.DefaultHttpClient
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import io.github.manamiproject.modb.core.excludeFromTestContext
import io.github.manamiproject.modb.core.random
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.DurationUnit.MILLISECONDS
import kotlin.time.toDuration
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
 * @property configRegistry Source of the registered client name and version and of `modb.anidb.banRetries`, the number of retries while banned. **Default:** `5`
 * @property httpClient To actually download the anime data.
 */
public class AnidbApiDownloader(
    private val metaDataProviderConfig: MetaDataProviderConfig = AnidbConfig,
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

    private val banRetries: Int by IntPropertyDelegate(
        configRegistry = configRegistry,
        namespace = CONFIG_NAMESPACE,
        default = DEFAULT_BAN_RETRIES,
        validator = { it >= 0 },
    )

    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String = withContext(LIMITED_NETWORK) {
        log.debug { "Downloading [anidbId=$id]" }

        var attempt = 0

        while (true) {
            try {
                return@withContext downloadOnce(id, onDeadEntry)
            } catch (e: AnidbBannedException) {
                attempt++

                if (attempt > banRetries) {
                    log.error { "Still banned by the API after [$banRetries] retries. Giving up on [anidbId=$id]." }
                    throw e
                }

                // Same waiting time as the retries of the http client. The ban is answered with
                // http 200, so the http client itself doesn't see it as a reason to retry.
                val waitTime = (random(MIN_BAN_WAIT_IN_MILLISECONDS, MAX_BAN_WAIT_IN_MILLISECONDS) * attempt).toDuration(MILLISECONDS)
                log.warn { "Banned by the API. Retry [$attempt/$banRetries] for [anidbId=$id] in [$waitTime]." }

                excludeFromTestContext(metaDataProviderConfig) {
                    delay(waitTime)
                }
            }
        }

        EMPTY_RESPONSE
    }

    private suspend fun downloadOnce(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String {
        val url = URI("${metaDataProviderConfig.buildDataDownloadLink(id)}&client=$client&clientver=$clientver&protover=$PROTOCOL_VERSION").toURL()
        val response = httpClient.get(url)
        val body = decompressIfNecessary(response.bodyAsByteArray())

        check(response.isOk()) { "Unexpected response code [anidbId=$id], [responseCode=${response.code}]" }
        check(body.isNotBlank()) { "Response body was blank for [anidbId=$id]." }

        val error = ERROR.find(body)

        if (error != null) {
            val code = error.groupValues[1]
            val message = error.groupValues[2].trim()

            // Not every error carries a code. An id which never existed is answered with a bare
            // <error>Anime not found</error>, so a check on the code alone lets that through and the
            // apology page ends up stored and converted as if it were an anime.
            if (code in DEAD_ENTRY_CODES || message.lowercase() in DEAD_ENTRY_MESSAGES) {
                // Whether the id is dead or still pending is decided by the caller, which logs the outcome.
                log.info { "The API responded with [$message] for [anidbId=$id]." }
                onDeadEntry.invoke(id)
                return EMPTY_RESPONSE
            }

            if (message.lowercase() == BANNED_MESSAGE) {
                throw AnidbBannedException("API responded with error [code=$code], [message=$message] for [anidbId=$id].")
            }

            throw IllegalStateException("API responded with error [code=$code], [message=$message] for [anidbId=$id].")
        }

        return body
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
        private const val BANNED_MESSAGE = "banned"
        private const val DEFAULT_BAN_RETRIES = 5
        private const val MIN_BAN_WAIT_IN_MILLISECONDS = 60000
        private const val MAX_BAN_WAIT_IN_MILLISECONDS = 90000
        private const val EMPTY_RESPONSE = ""
        private const val GZIP_MAGIC_FIRST: Byte = 0x1f
        private const val GZIP_MAGIC_SECOND: Byte = 0x8b.toByte()
        private val ERROR = Regex("""<error(?:\s+code="(\d+)")?\s*>([^<]*)</error>""")

        /**
         * Error codes which mean that the anime does not exist rather than that something went wrong.
         * @since 1.0.0
         */
        public val DEAD_ENTRY_CODES: Set<String> = setOf("330")

        /**
         * Lowercased messages of code-less errors which mean that the anime does not exist.
         * @since 1.0.0
         */
        public val DEAD_ENTRY_MESSAGES: Set<String> = setOf("anime not found")

        /**
         * Prefix of the properties read by this class.
         * @since 1.0.0
         */
        public const val CONFIG_NAMESPACE: String = "modb.anidb"
    }
}
