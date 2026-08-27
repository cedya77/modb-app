package io.github.manamiproject.modb.app.downloadcontrolstate

import io.github.manamiproject.modb.anidb.AnidbConfig
import io.github.manamiproject.modb.anilist.AnilistConfig
import io.github.manamiproject.modb.animeplanet.AnimePlanetConfig
import io.github.manamiproject.modb.anisearch.AnisearchConfig
import io.github.manamiproject.modb.core.anime.AnimeRaw
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.date.WeekOfYear
import io.github.manamiproject.modb.core.extensions.Directory
import io.github.manamiproject.modb.core.extensions.RegularFile
import io.github.manamiproject.modb.core.extensions.writeToFile
import io.github.manamiproject.modb.core.json.Json
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import io.github.manamiproject.modb.kitsu.KitsuConfig
import io.github.manamiproject.modb.livechart.LivechartConfig
import io.github.manamiproject.modb.myanimelist.MyanimelistConfig
import io.github.manamiproject.modb.serde.json.deserializer.DatasetFromJsonLinesInputStreamDeserializer
import io.github.manamiproject.modb.serde.json.deserializer.FromRegularFileDeserializer
import io.github.manamiproject.modb.serde.json.models.Dataset
import io.github.manamiproject.modb.simkl.SimklConfig
import io.github.manamiproject.AnimenewsnetworkConfig
import java.net.URI
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.math.absoluteValue

/**
 * Creates download control state entries from a published dataset.
 *
 * Without them the first run has to download every anime of every metadata provider at once, which
 * is both a burst of traffic that providers have no reason to tolerate and, in AniDB's case,
 * explicitly against the terms of their API. A published dataset already says which entries existed
 * and when they were last current, which is enough to schedule them instead of fetching them.
 *
 * Entries are spread across the coming quarter so the first refresh arrives as a steady trickle
 * rather than a single sweep. The anime stored in a seeded entry is the merged one rather than the
 * provider's own, so the first real download of each entry registers as a change. That costs one
 * refresh cycle and corrects itself.
 * @since 1.0.0
 * @property deserializer Reads the dataset file.
 * @property configurations Metadata providers to seed, keyed by the hostname used in a source URI.
 */
class DownloadControlStateSeeder(
    private val deserializer: FromRegularFileDeserializer<Dataset> = FromRegularFileDeserializer(
        deserializer = DatasetFromJsonLinesInputStreamDeserializer(),
    ),
    private val configurations: Set<MetaDataProviderConfig> = SUPPORTED_CONFIGURATIONS,
) {

    /**
     * Reads a dataset file and writes one download control state entry per source it names.
     * @since 1.0.0
     * @param dataset Dataset file to read. `json`, `jsonl` and their `zst` variants are supported.
     * @param downloadControlStateDirectory Directory to write the entries into.
     * @param currentWeek Week the schedule is relative to.
     * @return Number of entries written per metadata provider.
     */
    suspend fun seed(
        dataset: RegularFile,
        downloadControlStateDirectory: Directory,
        currentWeek: WeekOfYear = WeekOfYear(java.time.LocalDate.now()),
    ): Map<String, Int> {
        log.info { "Seeding download control state from [${dataset.toAbsolutePath()}]." }

        val byHostname = configurations.associateBy { it.hostname() }
        val written = mutableMapOf<String, Int>()

        deserializer.deserialize(dataset).data.forEach { anime ->
            anime.sources.forEach { source ->
                val config = byHostname[source.host] ?: return@forEach
                val animeId = config.extractAnimeId(source)
                val directory = downloadControlStateDirectory.resolve(config.hostname()).apply { createDirectories() }

                val entry = DownloadControlStateEntry(
                    _weeksWihoutChange = 0,
                    _lastDownloaded = currentWeek,
                    _nextDownload = currentWeek.plusWeeks(scheduleOffset(source)),
                    _anime = AnimeRaw(
                        _title = anime.title,
                        _sources = hashSetOf(source),
                        type = anime.type,
                        episodes = anime.episodes,
                        status = anime.status,
                        animeSeason = anime.animeSeason,
                        picture = anime.picture,
                        thumbnail = anime.thumbnail,
                        duration = anime.duration,
                        _synonyms = anime.synonyms.toHashSet(),
                        _studios = anime.studios.toHashSet(),
                        _producers = anime.producers.toHashSet(),
                        _relatedAnime = anime.relatedAnime.toHashSet(),
                        _tags = anime.tags.toHashSet(),
                    ),
                )

                Json.toJson(entry).writeToFile(directory.resolve("$animeId.$DOWNLOAD_CONTROL_STATE_FILE_SUFFIX"))
                written[config.hostname()] = (written[config.hostname()] ?: 0) + 1
            }
        }

        log.info { "Seeded download control state entries: $written" }

        return written
    }

    // Deriving the offset from the URI keeps the schedule stable across reruns, so seeding twice
    // does not reshuffle when everything is due.
    private fun scheduleOffset(source: URI): Int = source.toString().hashCode().absoluteValue % WEEKS_PER_QUARTER

    companion object {
        private val log by LoggerDelegate()

        private const val WEEKS_PER_QUARTER = 13

        /**
         * Metadata providers which appear as sources in the dataset.
         * @since 1.0.0
         */
        val SUPPORTED_CONFIGURATIONS: Set<MetaDataProviderConfig> = setOf(
            AnidbConfig,
            AnilistConfig,
            AnimePlanetConfig,
            AnimenewsnetworkConfig,
            AnisearchConfig,
            KitsuConfig,
            LivechartConfig,
            MyanimelistConfig,
            SimklConfig,
        )
    }
}

/**
 * Creates download control state entries from a published dataset file.
 *
 * Usage: `<dataset-file> <download-control-state-directory>`
 * @since 1.0.0
 */
fun main(args: Array<String>) {
    check(args.size == 2) { "Usage: <dataset-file> <download-control-state-directory>" }

    val written = kotlinx.coroutines.runBlocking {
        DownloadControlStateSeeder().seed(
            dataset = kotlin.io.path.Path(args[0]),
            downloadControlStateDirectory = kotlin.io.path.Path(args[1]),
        )
    }

    written.toSortedMap().forEach { (hostname, count) -> println("$hostname: $count") }
    println("total: ${written.values.sum()}")
}
