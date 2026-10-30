package io.github.manamiproject.modb.anilist

import io.github.manamiproject.modb.core.config.FileSuffix
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import java.net.URI

/**
 * Configuration for downloading and converting anime data from anilist.co
 * @since 1.0.0
 */
public object AnilistConfig : MetaDataProviderConfig {

    // Official public GraphQL API. The anime itself is still linked on the website (hostname).
    override fun buildDataDownloadLink(id: String): URI = URI("https://graphql.anilist.co")

    override fun hostname(): Hostname = "anilist.co"

    override fun fileSuffix(): FileSuffix = "json"
}