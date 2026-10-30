package io.github.manamiproject.modb.app

import io.github.manamiproject.modb.anidb.AnidbBannedException
import io.github.manamiproject.modb.anidb.CrawlerDetectedException
import io.github.manamiproject.modb.anidb.AnidbConfig
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
import io.github.manamiproject.modb.app.network.NetworkControllers
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

// Lazy, because the first logger sets up logback, which reads the configuration. That has to wait
// until ensureConfigFileExists() had the chance to create it.
@KoverIgnore
private val log by lazy { org.slf4j.LoggerFactory.getLogger("App") }

fun main() {
    ensureConfigFileExists()
    crawlAndCreateDataset()
}

private fun crawlAndCreateDataset() = runCoroutine {
    val disabled = DefaultConfigRegistry.instance.list<String>("modb.app.disabledCrawlers")?.toSet() ?: emptySet()

    // Only the crawlers of these sites change the outgoing address by restarting the network device.
    // Both aniSearch crawlers use the hostname of anisearch.com, but can be disabled independently.
    val sitesChangingAddress = buildSet {
        if (AnidbConfig.hostname() !in disabled) {
            add(AnidbConfig.hostname())
        }
        if (AnisearchConfig.hostname() !in disabled || ANISEARCH_RELATIONS_CRAWLER !in disabled) {
            add(AnisearchConfig.hostname())
        }
    }

    when {
        isRoot() -> log.info("Running as root. No sudo password needed.")
        !NetworkControllers.usesNetworkDevice(sitesChangingAddress) -> log.info("Network device won't be restarted. No sudo password needed.")
        else -> LinuxNetworkController.instance.sudoPasswordValue = verifiedSudoPassword()
    }

    val rawFileConversionService = DefaultRawFileConversionService.instance
    rawFileConversionService.start()

    val crawlers = mutableListOf<Job>()

    withContext(LIMITED_NETWORK) {
        crawl("anidb.net", disabled, crawlers) { AnidbCrawler.instance.start() }
        crawl("anilist.co", disabled, crawlers) { AnilistCrawler.instance.start() }
        crawl("anime-planet.com", disabled, crawlers) { AnimePlanetCrawler.instance.start() }
        crawl("animenewsnetwork.com", disabled, crawlers) { AnimenewsnetworkCrawler.instance.start() }
        crawl("anisearch.com", disabled, crawlers) { AnisearchCrawler(metaDataProviderConfig = AnisearchConfig).start() }
        crawl(ANISEARCH_RELATIONS_CRAWLER, disabled, crawlers) { AnisearchCrawler(metaDataProviderConfig = AnisearchRelationsConfig).start() }
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
        .removeAmbiguousSynonyms()
        .addAnimeCountdown()
        .transformToDatasetEntries()
        .saveToDataset()
        .updateStatistics()

    listOf(
        NoLockFilesLeftValidationPostProcessor.instance,
        DownloadControlStateWeeksValidationPostProcessor.instance,
        StudiosAndProducersExtractionChecker.instance,
        DuplicatesValidationPostProcessor.instance,
        DeadEntriesFileCreatorPostProcessor.instance,
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
private fun isRoot(): Boolean = try {
    val process = ProcessBuilder("id", "-u").redirectErrorStream(true).start()
    val userId = process.inputStream.bufferedReader().readText().trim()
    process.waitFor() == 0 && userId == "0"
} catch (_: Exception) {
    false
}

@KoverIgnore
private fun verifiedSudoPassword(): String {
    repeat(MAX_SUDO_ATTEMPTS) { attempt ->
        val password = passwordPrompt()

        if (isValidSudoPassword(password)) {
            return password
        }

        log.warn("Incorrect sudo password [${attempt + 1}/$MAX_SUDO_ATTEMPTS].")
    }

    log.error("No valid sudo password after [$MAX_SUDO_ATTEMPTS] attempts. Exiting.")
    exitProcess(1)
}

/**
 * `-k` ignores cached credentials so that the password is really checked, `-S` reads it from stdin
 * and `-v` only validates without running a command.
 */
@KoverIgnore
private fun isValidSudoPassword(password: String): Boolean = try {
    val process = ProcessBuilder("sudo", "-k", "-S", "-p", "", "-v").redirectErrorStream(true).start()
    process.outputStream.bufferedWriter().use { it.write(password); it.newLine() }
    process.inputStream.bufferedReader().readText()
    process.waitFor() == 0
} catch (_: Exception) {
    false
}

private const val MAX_SUDO_ATTEMPTS = 3
private const val ANISEARCH_RELATIONS_CRAWLER = "anisearch.com-relations"

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
        } catch (_: AnidbBannedException) {
            // Expected while a ban lasts, so a stack trace adds nothing.
            log.error("Crawler for [$hostname] stopped, because AniDB still answers with [banned] after all retries. Wait until the ban has been lifted or add [$hostname] to [modb.app.disabledCrawlers].")
        } catch (_: CrawlerDetectedException) {
            // AniDB refusing the connection is an answer, not a fault in the code, so a stack trace adds nothing.
            log.error("Crawler for [$hostname] stopped, because AniDB refuses the connection (AntiLeech or 403). Try again later or add [$hostname] to [modb.app.disabledCrawlers].")
        } catch (e: Throwable) {
            // The message says what happened. The stack trace is only of use when debugging, so it is logged at trace
            // level, which the regular log levels (down to debug) leave out.
            log.error("Crawler for [$hostname] stopped: ${e.message ?: e::class.simpleName}")
            log.trace("Stack trace of the stopped crawler for [$hostname]", e)
        }
    })
}
