package io.github.manamiproject.modb.app

import io.github.manamiproject.modb.anisearch.AnisearchConfig
import io.github.manamiproject.modb.anisearch.AnisearchRelationsConfig
import io.github.manamiproject.modb.app.convfiles.DefaultRawFileConversionService
import io.github.manamiproject.modb.app.crawlers.anidb.AnidbCrawler
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import io.github.manamiproject.modb.app.crawlers.anilist.AnilistCrawler
import io.github.manamiproject.modb.app.crawlers.animenewsnetwork.AnimenewsnetworkCrawler
import io.github.manamiproject.modb.app.crawlers.animeplanet.AnimePlanetCrawler
import io.github.manamiproject.modb.app.crawlers.anisearch.AnisearchCrawler
import io.github.manamiproject.modb.app.crawlers.kitsu.KitsuCrawler
import io.github.manamiproject.modb.app.crawlers.livechart.LivechartCrawler
import io.github.manamiproject.modb.app.crawlers.myanimelist.MyanimelistCrawler
import io.github.manamiproject.modb.app.crawlers.simkl.SimklCrawler
import io.github.manamiproject.modb.app.downloadcontrolstate.DefaultDownloadControlStateAccessor
import io.github.manamiproject.modb.app.downloadcontrolstate.DefaultDownloadControlStateUpdater
import io.github.manamiproject.modb.app.extensions.alertDeletedAnimeByTitle
import io.github.manamiproject.modb.app.fluentapi.*
import io.github.manamiproject.modb.app.network.LinuxNetworkController
import io.github.manamiproject.modb.app.postprocessors.*
import io.github.manamiproject.modb.core.coroutines.CoroutineManager.runCoroutine
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_NETWORK
import io.github.manamiproject.modb.core.coverage.KoverIgnore
import io.github.manamiproject.modb.core.extensions.EMPTY
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JOptionPane.*
import javax.swing.JPasswordField
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

@KoverIgnore
private val log = org.slf4j.LoggerFactory.getLogger("App")

fun main() = runCoroutine {
    LinuxNetworkController.instance.sudoPasswordValue = passwordPrompt()

    val rawFileConversionService = DefaultRawFileConversionService.instance
    rawFileConversionService.start()

    val disabled = DefaultConfigRegistry.instance.list<String>("modb.app.disabledCrawlers")?.toSet() ?: emptySet()

    val crawlers = mutableListOf<Job>()

    withContext(LIMITED_NETWORK) {
        crawl("anidb.net", disabled, crawlers) { AnidbCrawler.instance.start() }
        crawl("anilist.co", disabled, crawlers) { AnilistCrawler.instance.start() }
        crawl("anime-planet.com", disabled, crawlers) { AnimePlanetCrawler.instance.start() }
        crawl("animenewsnetwork.com", disabled, crawlers) { AnimenewsnetworkCrawler.instance.start() }
        crawl("anisearch.com", disabled, crawlers) { AnisearchCrawler(metaDataProviderConfig = AnisearchConfig).start() }
        crawl("anisearch.com-relations", disabled, crawlers) { AnisearchCrawler(metaDataProviderConfig = AnisearchRelationsConfig).start() }
        crawl("kitsu.app", disabled, crawlers) { KitsuCrawler.instance.start() }
        crawl("livechart.me", disabled, crawlers) { LivechartCrawler.instance.start() }
        crawl("myanimelist.net", disabled, crawlers) { MyanimelistCrawler.instance.start() }
        crawl("simkl.com", disabled, crawlers) { SimklCrawler.instance.start() }
    }

    crawlers.joinAll()

    rawFileConversionService.waitForAllRawFilesToBeConverted()
    rawFileConversionService.shutdown()

    DefaultDownloadControlStateUpdater.instance.updateAll()
    DefaultDownloadControlStateAccessor.instance.allAnime()
        .alertDeletedAnimeByTitle()
        .mergeAnime()
        .removeUnknownEntriesFromRelatedAnime()
        .addAnimeCountdown()
        .transformToDatasetEntries()
        .saveToDataset()
        .updateStatistics()

    listOf(
        NoLockFilesLeftValidationPostProcessor.instance,
        DownloadControlStateWeeksValidationPostProcessor.instance,
        StudiosAndProducersExtractionChecker.instance,
        DuplicatesValidationPostProcessor.instance,
        ZstandardFilesForDeadEntriesCreatorPostProcessor.instance,
        DeadEntriesValidationPostProcessor.instance,
        SourcesConsistencyValidationPostProcessor.instance,
        NumberOfEntriesValidationPostProcessor.instance,
        FileSizePlausibilityValidationPostProcessor.instance,
        DeleteOldDownloadDirectoriesPostProcessor.instance,
        ReleaseInfoFileCreatorPostProcessor.instance
    ).forEach { it.process() }
}

@KoverIgnore
private fun passwordPrompt(): String {
    val console = System.console()
    if (console != null) {
        return String(console.readPassword("sudo password:"))
    }

    return try {
        var ret = EMPTY
        SwingUtilities.invokeAndWait {
            val passwordField = JPasswordField()
            val options = arrayOf<Any>("OK", "Cancel")
            val option = showOptionDialog(
                null,
                passwordField,
                "sudo password:",
                NO_OPTION, PLAIN_MESSAGE,
                null,
                options,
                options[0],
            )
            when (option) {
                0 -> {
                    val passwordArray = passwordField.password
                    ret = String(passwordArray)
                }
                else -> exitProcess(0)
            }
        }
        ret
    } catch (_: Exception) {
        println("sudo password:")
        readlnOrNull() ?: EMPTY
    }
}

/**
 * Starts a crawler unless it has been switched off, and keeps its failure to itself.
 *
 * A provider can refuse to serve us for reasons which have nothing to do with the other eight, and
 * losing an entire run to one of them wastes every request the others already made. Whatever the
 * crawler managed to download stays on disk and is picked up by the conversion step either way.
 */
private fun CoroutineScope.crawl(hostname: String, disabled: Set<String>, jobs: MutableList<Job>, block: suspend () -> Unit) {
    if (hostname in disabled) {
        log.warn("Skipping crawler for [$hostname], because it is listed in [modb.app.disabledCrawlers].")
        return
    }

    jobs.add(launch {
        try {
            block.invoke()
        } catch (e: Throwable) {
            log.error("Crawler for [$hostname] stopped: ${e.message}", e)
        }
    })
}
