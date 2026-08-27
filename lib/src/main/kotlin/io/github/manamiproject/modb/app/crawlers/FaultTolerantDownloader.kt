package io.github.manamiproject.modb.app.crawlers

import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.IntPropertyDelegate
import io.github.manamiproject.modb.core.downloader.Downloader
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.core.logging.LoggerDelegate

/**
 * Lets a crawler skip an entry it cannot download instead of ending its run.
 *
 * A provider can refuse a single anime for reasons that say nothing about the rest: a page whose
 * markup drifted, an entry which is momentarily unavailable, a request answered with a status the
 * downloader does not recognise. Losing thousands of scheduled entries to one of them is a poor
 * trade, and the entry is scheduled again on the next run anyway.
 *
 * Failures in a row are a different matter. They suggest the provider has stopped serving us
 * altogether, in which case continuing wastes requests and produces a suspiciously thin week, so
 * the downloader gives up and lets the failure travel.
 *
 * Not every failure is about the entry that triggered it. Some say the connection it went out on
 * has been refused, which the crawler can recover from by leaving through another one. Skipping
 * those would spend the run's entries against a route already known to be dead, so a caller which
 * can recover names them and they travel untouched.
 * @since 1.0.0
 * @property downloader Performs the actual download.
 * @property hostname Named in the log so a skipped entry can be traced to a provider.
 * @property maxConsecutiveFailures Number of failures in a row tolerated before giving up.
 * @property isHandledByCaller Decides whether a failure is one the caller recovers from itself.
 */
class FaultTolerantDownloader(
    private val downloader: Downloader,
    private val hostname: String,
    configRegistry: ConfigRegistry = DefaultConfigRegistry.instance,
    private val isHandledByCaller: (Throwable) -> Boolean = { false },
): Downloader {

    private val maxConsecutiveFailures: Int by IntPropertyDelegate(
        configRegistry = configRegistry,
        namespace = CONFIG_NAMESPACE,
        default = DEFAULT_MAX_CONSECUTIVE_FAILURES,
    )

    private var consecutiveFailures = 0

    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String {
        return try {
            val response = downloader.download(id, onDeadEntry)
            consecutiveFailures = 0
            response
        } catch (e: Throwable) {
            if (isHandledByCaller(e)) {
                throw e
            }

            consecutiveFailures++

            if (consecutiveFailures >= maxConsecutiveFailures) {
                log.error { "Giving up on [$hostname] after [$consecutiveFailures] failures in a row." }
                throw e
            }

            log.warn { "Skipping [$hostname] entry [$id]: ${e.message}" }
            EMPTY
        }
    }

    companion object {
        private val log by LoggerDelegate()

        private const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 25

        /**
         * Prefix of the properties read by this class.
         * @since 1.0.0
         */
        const val CONFIG_NAMESPACE: String = "modb.app.crawler"
    }
}
