package io.github.manamiproject.modb.app.merging

import io.github.manamiproject.modb.animeplanet.AnimePlanetConfig
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_CPU
import io.github.manamiproject.modb.core.extractor.DataExtractor
import io.github.manamiproject.modb.core.extractor.XmlDataExtractor
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import io.github.manamiproject.modb.myanimelist.MyanimelistConfig
import kotlinx.coroutines.withContext
import java.net.URI
import io.github.manamiproject.AnimenewsnetworkConfig

/**
 * Reads the links to other metadata providers which AniDB publishes alongside an anime.
 *
 * The merging process otherwise has to infer that two entries describe the same anime from how
 * similar their titles are. AniDB states some of those relationships outright, which is a stronger
 * signal than any similarity score.
 *
 * A resource can name more than one entry on the same provider, which happens when the other
 * provider splits what AniDB keeps as a single anime. Those are dropped rather than guessed at,
 * because merging the wrong entries together is worse than not merging them at all.
 * @since 1.0.0
 * @property extractor Extractor which retrieves the data from raw data.
 */
class AnidbCrossReferences(
    private val extractor: DataExtractor = XmlDataExtractor,
) {

    /**
     * @since 1.0.0
     * @param rawContent Response of the AniDB HTTP API for a single anime.
     * @return Links to the same anime on other metadata providers. Empty if AniDB names none.
     */
    suspend fun extract(rawContent: String): Set<URI> = withContext(LIMITED_CPU) {
        val identifiers = extractor.extract(rawContent, SUPPORTED_RESOURCES.mapValues { (_, type) ->
            "//resources/resource[@type='$type']/externalentity/identifier/text()"
        }.mapKeys { (config, _) -> config.hostname() })

        return@withContext SUPPORTED_RESOURCES.keys.mapNotNull { config ->
            val key = config.hostname()

            if (identifiers.notFound(key)) {
                return@mapNotNull null
            }

            val ids = identifiers.listNotNull<String>(key).map { it.trim() }.filter { it.isNotBlank() }

            when (ids.size) {
                1 -> config.buildAnimeLink(ids.first())
                0 -> null
                else -> {
                    log.debug { "Ignoring [${config.hostname()}] cross reference, because AniDB names [${ids.size}] entries: $ids" }
                    null
                }
            }
        }.toSet()
    }

    companion object {
        private val log by LoggerDelegate()

        /**
         * Resource types which map onto a metadata provider of this dataset. The numbers are AniDB's
         * own and were confirmed against API responses rather than taken from documentation.
         * @since 1.0.0
         */
        val SUPPORTED_RESOURCES: Map<MetaDataProviderConfig, Int> = mapOf(
            AnimenewsnetworkConfig to 1,
            MyanimelistConfig to 2,
            AnimePlanetConfig to 45,
        )

        /**
         * Singleton of [AnidbCrossReferences].
         * @since 1.0.0
         */
        val instance: AnidbCrossReferences by lazy { AnidbCrossReferences() }
    }
}
