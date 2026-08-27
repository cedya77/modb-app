package io.github.manamiproject.modb.anidb

import io.github.manamiproject.modb.core.config.FileSuffix
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig

/**
 * Configuration for reading anime data from the anidb.net website.
 *
 * Kept for [AnidbDownloader] and [AnidbAnimeConverter], which read the page rather than the API.
 * The pipeline itself uses [AnidbConfig], since the website refuses automated access.
 * @since 1.0.0
 */
public object AnidbWebsiteConfig : MetaDataProviderConfig by AnidbConfig {

    override fun buildDataDownloadLink(id: String): java.net.URI = buildAnimeLink(id)

    override fun fileSuffix(): FileSuffix = "html"
}
