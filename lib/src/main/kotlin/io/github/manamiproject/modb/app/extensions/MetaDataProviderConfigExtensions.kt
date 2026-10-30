package io.github.manamiproject.modb.app.extensions

import io.github.manamiproject.modb.anisearch.AnisearchRelationsConfig
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig

/**
 * Name of the crawler to use in log messages. Equals the hostname, except for crawlers which share a hostname with
 * another one. The names are the same as the ones used for `modb.app.disabledCrawlers`.
 * @since 20.0.0
 * @receiver Config of the crawler.
 * @return Name which identifies the crawler.
 */
fun MetaDataProviderConfig.siteName(): String = when (this) {
    AnisearchRelationsConfig -> "${hostname()}-relations"
    else -> hostname()
}
