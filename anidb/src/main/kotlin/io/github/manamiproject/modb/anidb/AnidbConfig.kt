package io.github.manamiproject.modb.anidb

import io.github.manamiproject.modb.core.config.FileSuffix
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import java.net.URI

/**
 * Configuration for anime data from anidb.net.
 *
 * Data is read through the HTTP API, which is the access path AniDB sanctions for third parties and
 * which is not behind the protection the website sits behind. Anime links still point at the website,
 * because that is the identity an entry has in the dataset.
 * @since 1.0.0
 */
public object AnidbConfig : MetaDataProviderConfig {

    override fun hostname(): Hostname = "anidb.net"

    override fun buildDataDownloadLink(id: String): URI = URI("$API_URL?request=anime&aid=$id")

    override fun fileSuffix(): FileSuffix = "xml"

    /**
     * Base URL of the HTTP API. Requests additionally require a registered client name and version.
     * @since 1.0.0
     */
    public const val API_URL: String = "http://api.anidb.net:9001/httpapi"
}
