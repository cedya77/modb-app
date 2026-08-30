package io.github.manamiproject.modb.app.crawlers

import io.github.manamiproject.modb.app.convfiles.AlreadyDownloadedIdsFinder
import io.github.manamiproject.modb.app.convfiles.DefaultAlreadyDownloadedIdsFinder
import io.github.manamiproject.modb.app.dataset.DeadEntriesAccessor
import io.github.manamiproject.modb.app.dataset.DefaultDeadEntriesAccessor
import io.github.manamiproject.modb.app.downloadcontrolstate.DefaultDownloadControlStateAccessor
import io.github.manamiproject.modb.app.downloadcontrolstate.DefaultDownloadControlStateScheduler
import io.github.manamiproject.modb.app.downloadcontrolstate.DownloadControlStateAccessor
import io.github.manamiproject.modb.app.downloadcontrolstate.DownloadControlStateScheduler
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.IntPropertyDelegate
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.extensions.createShuffledList
import io.github.manamiproject.modb.core.logging.LoggerDelegate

/**
 * Generates a sequence of anime IDs of type [Int] for a specific metadata provider.
 * 1. First the highest anime ID that the metadata provider has to offer is fetched.
 * 2. A sequence from 1 (inclusive) to the highest id (inclusive) is generated.
 * 3. Dead entries, anime which are not scheduled for the current week as well as already downloaded anime are removed
 * 4. Resulting list is shuffled and returned.
 * @since 1.0.0
 * @property metaDataProviderConfig Configuration for a specific metadata provider.
 * @property highestIdDetector Allows to retrieve the highest anime ID currently available on the metadata provider website.
 * @property deadEntriesAccessor Access to dead entries files.
 * @property downloadControlStateAccessor Access to DCS files.
 * @property downloadControlStateScheduler Allows to check which anime are scheduled for re-download and which are not.
 * @property alreadyDownloadedIdsFinder Fetches all IDs which have already been downloaded.
 */
class IntegerBasedIdRangeSelector(
    private val metaDataProviderConfig: MetaDataProviderConfig,
    private val highestIdDetector: HighestIdDetector,
    private val deadEntriesAccessor: DeadEntriesAccessor = DefaultDeadEntriesAccessor.instance,
    private val downloadControlStateAccessor: DownloadControlStateAccessor = DefaultDownloadControlStateAccessor.instance,
    private val downloadControlStateScheduler: DownloadControlStateScheduler = DefaultDownloadControlStateScheduler.instance,
    private val alreadyDownloadedIdsFinder: AlreadyDownloadedIdsFinder = DefaultAlreadyDownloadedIdsFinder.instance,
    configRegistry: ConfigRegistry = DefaultConfigRegistry.instance,
): IdRangeSelector<Int> {

    private val deadEntriesToRecheck: Int by IntPropertyDelegate(
        configRegistry = configRegistry,
        namespace = CONFIG_NAMESPACE,
        default = DEFAULT_DEAD_ENTRIES_TO_RECHECK,
    )

    override suspend fun idDownloadList(): List<Int> {
        log.info { "Creating a list of IDs to download for [${metaDataProviderConfig.hostname()}]." }

        val highestId = highestIdDetector.detectHighestId()
        log.debug { "Highest ID for [${metaDataProviderConfig.hostname()}] is [$highestId]" }
        check(highestId > 0) { "Highest ID must be greater than 0" }

        val highestIdAlreadyInDataset = downloadControlStateAccessor.highestIdAlreadyInDataset(metaDataProviderConfig)
        check(highestId >= highestIdAlreadyInDataset) { "Quality assurance problem for [${metaDataProviderConfig.hostname()}]. Detected highest ID [$highestId] is smaller than the highest ID already in dataset [$highestIdAlreadyInDataset]." }

        val possibleIds = hashSetOf<Int>()
        for (i in 1..highestId) {
            possibleIds.add(i)
        }

        log.debug { "Having [${possibleIds.size}] for [${metaDataProviderConfig.hostname()}] before excluding dead entries." }

        // An id which answered that it does not exist is excluded from every later run, so a
        // provider which publishes an entry under an id it had previously refused keeps that entry
        // out of the dataset for good. The highest ids are the ones this happens to, because they
        // are the ones a provider is still filling in, so a slice of them is looked at again.
        val deadEntries = deadEntriesAccessor.fetchDeadEntries(metaDataProviderConfig).map { it.toInt() }.toSet()
        val recheck = deadEntries.sortedDescending().take(deadEntriesToRecheck).toSet()

        if (recheck.isNotEmpty()) {
            log.info { "Rechecking [${recheck.size}] of [${deadEntries.size}] dead entries for [${metaDataProviderConfig.hostname()}]." }
        }

        possibleIds.removeAll(deadEntries - recheck)
        log.debug { "Having [${possibleIds.size}] for [${metaDataProviderConfig.hostname()}] after excluding dead entries." }

        log.debug { "Having [${possibleIds.size}] for [${metaDataProviderConfig.hostname()}] before excluding entries which are not scheduled for the current week." }
        possibleIds.removeAll(downloadControlStateScheduler.findEntriesNotScheduledForCurrentWeek(metaDataProviderConfig).map { it.toInt() }.toSet())
        log.debug { "Having [${possibleIds.size}] for [${metaDataProviderConfig.hostname()}] after excluding entries which are not scheduled for the current week." }

        log.debug { "Having [${possibleIds.size}] for [${metaDataProviderConfig.hostname()}] before excluding already downloaded entries." }
        possibleIds.removeAll(alreadyDownloadedIdsFinder.alreadyDownloadedIds(metaDataProviderConfig).map { it.toInt() }.toSet())
        log.debug { "Having [${possibleIds.size}] for [${metaDataProviderConfig.hostname()}] after excluding already downloaded entries." }

        return possibleIds.toList().createShuffledList()
    }

    companion object {
        private val log by LoggerDelegate()

        /**
         * Dead entries are not rechecked unless a deployment asks for it.
         * @since 1.0.0
         */
        private const val DEFAULT_DEAD_ENTRIES_TO_RECHECK = 0

        /**
         * Prefix of the properties read by this class.
         * @since 1.0.0
         */
        const val CONFIG_NAMESPACE: String = "modb.app.crawler"
    }
}