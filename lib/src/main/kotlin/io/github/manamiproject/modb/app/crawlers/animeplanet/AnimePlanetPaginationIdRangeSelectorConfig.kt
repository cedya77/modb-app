package io.github.manamiproject.modb.app.crawlers.animeplanet

import io.github.manamiproject.modb.animeplanet.AnimePlanetConfig
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import java.net.URI

/**
 * Configuration for creating the id range for anime-planet.com
 * @since 1.0.0
 */
object AnimePlanetPaginationIdRangeSelectorConfig: MetaDataProviderConfig by AnimePlanetConfig {

    // No view mode is asked for. It is stored in a cookie rather than applied to the response, so
    // asking only costs a redirect and still returns whichever layout the cookie already held.
    override fun buildDataDownloadLink(id: String): URI = URI("https://${hostname()}/anime/all?sort=title&order=asc&page=$id")
}