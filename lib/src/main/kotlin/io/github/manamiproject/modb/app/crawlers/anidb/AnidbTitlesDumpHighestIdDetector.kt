package io.github.manamiproject.modb.app.crawlers.anidb

import io.github.manamiproject.modb.app.crawlers.HighestIdDetector
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_NETWORK
import io.github.manamiproject.modb.core.httpclient.DefaultHttpClient
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.zip.GZIPInputStream

/**
 * Determines the highest anime id from the title dump which AniDB publishes.
 *
 * The dump is a plain file rather than part of the website, so it is reachable without the
 * protection that anidb.net sits behind, and AniDB publishes it precisely so that clients do not
 * have to search or crawl for ids. It is regenerated daily and names every anime that exists, which
 * also means ids that were never assigned are not requested at all.
 * @since 1.0.0
 * @property httpClient To download the dump.
 * @property url Location of the dump.
 */
class AnidbTitlesDumpHighestIdDetector(
    private val httpClient: HttpClient = DefaultHttpClient(),
    private val url: URI = URI(TITLES_DUMP_URL),
): HighestIdDetector {

    override suspend fun detectHighestId(): Int = withContext(LIMITED_NETWORK) {
        log.info { "Fetching the AniDB title dump to determine the highest id." }

        val body = httpClient.get(url.toURL()).bodyAsByteArray()
        val ids = parse(body)

        check(ids.isNotEmpty()) { "Title dump did not contain any anime id." }

        log.info { "Title dump names [${ids.size}] anime, the highest id being [${ids.max()}]." }

        return@withContext ids.max()
    }

    /**
     * Every anime id named by the dump.
     * @since 1.0.0
     * @return Ids of all anime which exist on AniDB.
     */
    suspend fun allIds(): Set<Int> = withContext(LIMITED_NETWORK) {
        return@withContext parse(httpClient.get(url.toURL()).bodyAsByteArray())
    }

    private fun parse(body: ByteArray): Set<Int> {
        // The file is gzipped content rather than a compressed transfer, so the http client has no
        // reason to unwrap it.
        val isGzipped = body.size > 1 && body[0] == GZIP_MAGIC_FIRST && body[1] == GZIP_MAGIC_SECOND
        val text = when {
            isGzipped -> GZIPInputStream(ByteArrayInputStream(body)).bufferedReader().use { it.readText() }
            else -> body.decodeToString()
        }

        return text.lineSequence()
            .filterNot { it.startsWith(COMMENT) || it.isBlank() }
            .mapNotNull { it.substringBefore(SEPARATOR).trim().toIntOrNull() }
            .toSet()
    }

    companion object {
        private val log by LoggerDelegate()

        private const val COMMENT = "#"
        private const val SEPARATOR = '|'
        private const val GZIP_MAGIC_FIRST: Byte = 0x1f
        private const val GZIP_MAGIC_SECOND: Byte = 0x8b.toByte()

        /**
         * Location of the title dump.
         * @since 1.0.0
         */
        const val TITLES_DUMP_URL = "https://anidb.net/api/anime-titles.dat.gz"

        /**
         * Singleton of [AnidbTitlesDumpHighestIdDetector].
         * @since 1.0.0
         */
        val instance: AnidbTitlesDumpHighestIdDetector by lazy { AnidbTitlesDumpHighestIdDetector() }
    }
}
