package io.github.manamiproject.modb.anidb

import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.FileSuffix
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import java.net.URI

/**
 * Configuration for reading anime data from the AniDB HTTP API.
 *
 * The API answers on a separate host and port which is not behind the protection that anidb.net
 * itself sits behind, and it is the access path which AniDB sanctions for third parties. Anime links
 * still point at anidb.net, because that is the identity an entry has in the dataset.
 * @since 1.0.0
 */
public object AnidbApiConfig : MetaDataProviderConfig {

    override fun hostname(): Hostname = AnidbConfig.hostname()

    override fun buildAnimeLink(id: AnimeId): URI = AnidbConfig.buildAnimeLink(id)

    override fun buildDataDownloadLink(id: String): URI = URI("$API_URL?request=anime&aid=$id")

    override fun fileSuffix(): FileSuffix = "xml"

    /**
     * Base URL of the HTTP API. Requests additionally require a registered client name and version.
     * @since 1.0.0
     */
    public const val API_URL: String = "http://api.anidb.net:9001/httpapi"
}
