package io.github.manamiproject.modb.app.crawlers.anidb

import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.app.crawlers.HighestIdDetector
import io.github.manamiproject.modb.app.dataset.DeadEntriesAccessor
import io.github.manamiproject.modb.app.dataset.DefaultDeadEntriesAccessor
import io.github.manamiproject.modb.app.downloadcontrolstate.DefaultDownloadControlStateAccessor
import io.github.manamiproject.modb.app.downloadcontrolstate.DownloadControlStateAccessor
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_NETWORK
import io.github.manamiproject.modb.core.extensions.RegularFile
import io.github.manamiproject.modb.core.extensions.regularFileExists
import io.github.manamiproject.modb.core.httpclient.DefaultHttpClient
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * Determines the highest anime id from the title dump which AniDB publishes and derives the dead entries from it.
 *
 * The dump is a plain file rather than part of the website, so it is reachable without the
 * protection that anidb.net sits behind, and AniDB publishes it precisely so that clients do not
 * have to search or crawl for ids. It is regenerated daily and names every anime that exists, which
 * also means ids that were never assigned or have been deleted are not requested at all: every id
 * from 1 to the highest id which the dump doesn't name becomes a dead entry.
 *
 * AniDB asks not to download the dump more than once per day. The last download is kept in the
 * download control state directory and is only replaced once it is older than [maxAge].
 * @since 1.0.0
 * @property appConfig Application specific configuration. Uses [AppConfig] by default.
 * @property httpClient To download the dump.
 * @property url Location of the dump.
 * @property deadEntriesAccessor Access to dead entries files.
 * @property downloadControlStateAccessor Access to DCS files.
 * @property maxAge Age after which the kept dump is downloaded again.
 * @property metaDataProviderConfig Config of the configured AniDB source, under which the dead entries are kept.
 */
class AnidbTitlesDumpHighestIdDetector(
    private val appConfig: Config = AppConfig.instance,
    private val httpClient: HttpClient = DefaultHttpClient(),
    private val url: URI = URI(TITLES_DUMP_URL),
    private val deadEntriesAccessor: DeadEntriesAccessor = DefaultDeadEntriesAccessor.instance,
    private val downloadControlStateAccessor: DownloadControlStateAccessor = DefaultDownloadControlStateAccessor.instance,
    private val maxAge: Duration = Duration.ofDays(1),
    private val metaDataProviderConfig: MetaDataProviderConfig = AnidbSource.config(),
): HighestIdDetector {

    private val dumpAccess = Mutex()

    override suspend fun detectHighestId(): Int = withContext(LIMITED_NETWORK) {
        val ids = allIds()
        val highestId = ids.max()

        log.info { "Title dump names [${ids.size}] anime, the highest id being [$highestId]." }

        updateDeadEntries(ids, highestId)

        return@withContext highestId
    }

    /**
     * Every anime id named by the dump.
     * @since 1.0.0
     * @return Ids of all anime which exist on AniDB.
     */
    suspend fun allIds(): Set<Int> = withContext(LIMITED_NETWORK) {
        val ids = parse(dump())
        check(ids.isNotEmpty()) { "Title dump did not contain any anime id." }
        return@withContext ids
    }

    private suspend fun dump(): ByteArray = dumpAccess.withLock {
        val file = dumpFile()
        val now = appConfig.clock().instant()
        val lastDownload = if (file.regularFileExists()) file.getLastModifiedTime().toInstant() else Instant.EPOCH

        if (Duration.between(lastDownload, now) < maxAge) {
            val nextDownload = lastDownload.plus(maxAge)
            log.info { "Using the AniDB title dump from [${format(lastDownload)}]. Next download possible from [${format(nextDownload)}] (in [${formatDuration(Duration.between(now, nextDownload))}])." }
            return@withLock file.readBytes()
        }

        log.info { "Downloading the AniDB title dump from [$url]." }

        try {
            val body = httpClient.get(url.toURL()).bodyAsByteArray()

            // A broken or empty download must not replace a working dump, because the dead
            // entries are derived from it.
            check(parse(body).isNotEmpty()) { "Title dump did not contain any anime id." }

            val tempFile = file.resolveSibling("${file.fileName}.tmp")
            tempFile.writeBytes(body)
            Files.move(tempFile, file, REPLACE_EXISTING, ATOMIC_MOVE)

            return@withLock body
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!file.regularFileExists()) {
                throw e
            }

            log.warn { "Unable to download the AniDB title dump [${e.message}]. Using the one from [${format(lastDownload)}] instead." }
            return@withLock file.readBytes()
        }
    }

    private suspend fun updateDeadEntries(ids: Set<Int>, highestId: Int) {
        // Each dead entry removes the corresponding DCS file. If the dump names fewer anime than
        // the dataset already has, it is more likely to be broken than AniDB having deleted them.
        val highestIdAlreadyInDataset = downloadControlStateAccessor.highestIdAlreadyInDataset(metaDataProviderConfig)

        if (highestId < highestIdAlreadyInDataset) {
            log.warn { "Not updating the [${metaDataProviderConfig.hostname()}] dead entries, because the highest id of the title dump [$highestId] is smaller than the highest id already in the dataset [$highestIdAlreadyInDataset]." }
            return
        }

        val deadEntries = (1..highestId)
            .filterNot { ids.contains(it) }
            .map { it.toString() }
            .toSet()

        deadEntriesAccessor.replaceDeadEntries(deadEntries, metaDataProviderConfig)
    }

    private fun format(instant: Instant): String = TIMESTAMP_FORMAT.format(instant.atZone(appConfig.clock().zone))

    private fun formatDuration(duration: Duration): String = "%dh %02dm".format(duration.toHours(), duration.toMinutesPart())

    private fun dumpFile(): RegularFile = appConfig.downloadControlStateDirectory().resolve(DUMP_FILE_NAME)

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
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private const val SEPARATOR = '|'
        private const val GZIP_MAGIC_FIRST: Byte = 0x1f
        private const val GZIP_MAGIC_SECOND: Byte = 0x8b.toByte()

        /**
         * Location of the title dump.
         * @since 1.0.0
         */
        const val TITLES_DUMP_URL = "https://anidb.net/api/anime-titles.dat.gz"

        /**
         * Name of the file in the download control state directory which keeps the last download of the dump.
         * @since 1.0.0
         */
        const val DUMP_FILE_NAME = "anidb-anime-titles.dat.gz"

        /**
         * Singleton of [AnidbTitlesDumpHighestIdDetector].
         * @since 1.0.0
         */
        val instance: AnidbTitlesDumpHighestIdDetector by lazy { AnidbTitlesDumpHighestIdDetector() }
    }
}
