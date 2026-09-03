package io.github.manamiproject.modb.app.downloadcontrolstate

import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.app.convfiles.CONVERTED_FILE_SUFFIX
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.IntPropertyDelegate
import io.github.manamiproject.modb.core.config.ListPropertyDelegate
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_FS
import io.github.manamiproject.modb.core.extensions.fileName
import io.github.manamiproject.modb.core.extensions.listRegularFiles
import io.github.manamiproject.modb.core.extensions.readFile
import io.github.manamiproject.modb.core.json.Json
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import io.github.manamiproject.modb.core.anime.AnimeRaw
import io.github.manamiproject.modb.core.date.WeekOfYear
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * This implementation of [DownloadControlStateUpdater] checks and updates DCS files.
 * There are some important key factors here.
 *
 * This class can only be executed once in a meaningful way per weekly update.
 * The reason is that before actually updating the DCS entries it also checks the newly created conv filed against
 * the already existing DCS files for possible problems in the converter classes. This is the only point of time when
 * it is possible and only before the update took place. That's way it is baked into the [updateAll] function instead of
 * being offered as a public function and then orchestrated elsewhere.
 * This is also the where the anime are checked for IDs being updated. Conv files are already accessed here and it's
 * supposed to be done prior to updating the DCS files. That's why it's also part of the [updateAll].
 *
 * Therefore, this class is mostly a quality gate and process orchestrator for [DownloadControlStateAccessor].
 * @since 1.0.0
 * @property appConfig Application specific configuration. Uses [AppConfig] by default.
 * @property downloadControlStateAccessor Access to DCS files.
 */
class DefaultDownloadControlStateUpdater(
    private val appConfig: Config = AppConfig.instance,
    private val downloadControlStateAccessor: DownloadControlStateAccessor = DefaultDownloadControlStateAccessor.instance,
    configRegistry: ConfigRegistry = DefaultConfigRegistry.instance,
): DownloadControlStateUpdater {

    private val qualityScoreThreshold: Int by IntPropertyDelegate(
        configRegistry = configRegistry,
        namespace = CONFIG_NAMESPACE,
        default = DEFAULT_QUALITY_SCORE_THRESHOLD,
    )

    private val disabledCrawlers: List<String> by ListPropertyDelegate(
        configRegistry = configRegistry,
        namespace = APP_NAMESPACE,
        default = emptyList(),
    )

    override suspend fun updateAll() = withContext(LIMITED_FS) {
        val convFileAnimeToFilename = fetchAnimeFromConvFiles()
        val animeToProvider = convFileAnimeToFilename.map { it.first to appConfig.findMetaDataProviderConfig(it.first.sources.first().host) }

        checkForExtractionProblems(animeToProvider)
        updateChangedIds(convFileAnimeToFilename)

        animeToProvider.map { (anime, metaDataProviderConfig) ->
            launch { handleUpdate(anime, metaDataProviderConfig) }
        }.joinAll()

        rescheduleEntriesWhichHaveNotBeenFetched()
    }

    /**
     * A provider can stop serving us part way through its list. The entries it never answered for
     * keep next-download set to the current week, which the week validation refuses, so a single
     * provider cutting out ends the run with nothing published. They are moved to the following week
     * instead, which is where they would have landed had the entry been fetched and found unchanged.
     * Last-downloaded and the weeks-without-change counter are left alone, because no download
     * happened. Providers whose crawler was switched off are skipped, otherwise a run which
     * deliberately crawls nothing would reschedule the whole dataset.
     */
    private suspend fun rescheduleEntriesWhichHaveNotBeenFetched() {
        val currentWeek = WeekOfYear.currentWeek()

        appConfig.metaDataProviderConfigurations()
            .filterNot { it.hostname() in disabledCrawlers }
            .forEach { metaDataProviderConfig ->
                val fetched = appConfig.workingDir(metaDataProviderConfig)
                    .listRegularFiles("*.$CONVERTED_FILE_SUFFIX")
                    .map { it.fileName().substringBeforeLast('.') }
                    .toSet()

                val stranded = downloadControlStateAccessor.allDcsEntries(metaDataProviderConfig)
                    .filter { it.nextDownload == currentWeek }
                    .map { metaDataProviderConfig.extractAnimeId(it.anime.sources.first()) to it }
                    .filterNot { (animeId, _) -> animeId in fetched }

                if (stranded.isEmpty()) {
                    return@forEach
                }

                log.warn { "Rescheduling [${stranded.size}] entries for [${metaDataProviderConfig.hostname()}] which were due this week, but never fetched." }

                stranded.forEach { (animeId, entry) ->
                    downloadControlStateAccessor.createOrUpdate(
                        metaDataProviderConfig,
                        animeId,
                        entry.copy(_nextDownload = currentWeek.plusWeeks(1)),
                    )
                }
            }
    }

    private suspend fun fetchAnimeFromConvFiles(): List<Pair<AnimeRaw, String>> = withContext(LIMITED_FS) {
        log.info { "Loading [*.$CONVERTED_FILE_SUFFIX] files." }

        val jobsResults = appConfig.metaDataProviderConfigurations()
            .map { metaDataProviderConfig ->
                appConfig.workingDir(metaDataProviderConfig)
            }
            .map { workDir ->
                workDir.listRegularFiles("*.$CONVERTED_FILE_SUFFIX").map { file ->
                    async {
                        Json.parseJson<AnimeRaw>(file.readFile())!! to file.fileName()
                    }
                }
            }.flatten().awaitAll()

        return@withContext jobsResults
    }

    private suspend fun checkForExtractionProblems(convFileAnime: List<Pair<AnimeRaw, MetaDataProviderConfig>>) {
        log.info { "Checking for possible extraction problems in the converter classes." }

        val counter = mutableMapOf<MetaDataProviderConfig, UInt>()
        val score = mutableMapOf<MetaDataProviderConfig, UInt>()

        appConfig.metaDataProviderConfigurations().forEach { metaDataProviderConfig ->
            counter[metaDataProviderConfig] = 0u
            score[metaDataProviderConfig] = 0u
        }

        convFileAnime.forEach { (anime, metaDataProviderConfig) ->
            val animeId = metaDataProviderConfig.extractAnimeId(anime.sources.first())

            if (downloadControlStateAccessor.dcsEntryExists(metaDataProviderConfig, animeId)) {
                val dcsEntry = downloadControlStateAccessor.dcsEntry(metaDataProviderConfig, animeId)
                val currentScore = dcsEntry.calculateQualityScore(anime)
                counter[metaDataProviderConfig] = counter[metaDataProviderConfig]!!.inc()
                score[metaDataProviderConfig] = score[metaDataProviderConfig]!! + currentScore
            }
        }

        val faulty = mutableListOf<String>()

        score.forEach { (metaDataProviderConfig, score) ->
            val numberOfFiles = counter[metaDataProviderConfig] ?: 0u
            val percentage = when(score) {
                0u -> 0u
                else -> (score.toDouble() / numberOfFiles.toDouble() * 100.0).toUInt()
            }

            if (percentage >= qualityScoreThreshold.toUInt()) {
                faulty.add("${metaDataProviderConfig.hostname()} with a percentage of $percentage")
            }
        }

        check(faulty.isEmpty()) {
            val message = StringBuilder("Possibly found a problem in the extraction of data:")
            faulty.forEach { message.append("\n  * $it") }
            message.toString()
        }
    }

    private suspend fun updateChangedIds(convFileAnimeToFilename: List<Pair<AnimeRaw, String>>) {
        log.info { "Checking if IDs have changed." }

        convFileAnimeToFilename.forEach { (anime, fileName) ->
            val metaDataProviderConfig = appConfig.findMetaDataProviderConfig(anime.sources.first().host)
            val fileId = fileName.removeSuffix(".$CONVERTED_FILE_SUFFIX")
            val animeId = metaDataProviderConfig.extractAnimeId(anime.sources.first())

            if (animeId != fileId) {
                check(appConfig.canChangeAnimeIds(metaDataProviderConfig)) { "Detected ID change from [$fileId] to [$animeId] although [${metaDataProviderConfig.hostname()}] doesn't support changing IDs." }
                downloadControlStateAccessor.changeId(fileId, animeId, metaDataProviderConfig)
            }
        }
    }

    private suspend fun handleUpdate(anime: AnimeRaw, metaDataProviderConfig: MetaDataProviderConfig) {
        val animeId = metaDataProviderConfig.extractAnimeId(anime.sources.first())

        val dcsEntry = when(downloadControlStateAccessor.dcsEntryExists(metaDataProviderConfig, animeId)) {
            false -> DownloadControlStateEntry(
                _weeksWihoutChange = 0,
                _lastDownloaded = WeekOfYear.currentWeek(),
                _nextDownload = WeekOfYear.currentWeek().plusWeeks(1),
                _anime = anime,
            )
            else -> {
                val downloadControlStateEntry = downloadControlStateAccessor.dcsEntry(metaDataProviderConfig, animeId)
                downloadControlStateEntry.update(anime)
            }
        }

        downloadControlStateAccessor.createOrUpdate(metaDataProviderConfig, animeId, dcsEntry)
    }

    companion object {
        private val log by LoggerDelegate()

        /**
         * How much poorer than what is already known an extraction may be before the run is stopped.
         *
         * The comparison is against what the download control state holds, so it only means what it
         * says once that state was written by this pipeline. State seeded from a published dataset
         * holds entries merged from every provider, which carry more synonyms and relations than any
         * single provider ever returns, so a seeded corpus reports a shortfall for honest data and
         * needs the threshold lifted until one full cycle has replaced it.
         * @since 1.0.0
         */
        private const val DEFAULT_QUALITY_SCORE_THRESHOLD = 25

        /**
         * Namespace holding the list of crawlers which have been switched off.
         * @since 1.0.0
         */
        const val APP_NAMESPACE: String = "modb.app"

        /**
         * Prefix of the properties read by this class.
         * @since 1.0.0
         */
        const val CONFIG_NAMESPACE: String = "modb.app.dcs"


        /**
         * Singleton of [DefaultDownloadControlStateUpdater]
         * @since 1.0.0
         */
        val instance: DefaultDownloadControlStateUpdater by lazy { DefaultDownloadControlStateUpdater() }
    }
}