package io.github.manamiproject.modb.core.anime

import io.github.manamiproject.modb.core.extensions.neitherNullNorBlank
import io.github.manamiproject.modb.core.extensions.normalize
import java.net.URI

/**
 * Kind of relation between two anime exactly as the metadata provider labels it.
 * Examples: `Sequel` (myanimelist), `SIDE_STORY` (anilist), `Side Story` (anidb).
 * The labels are not unified across metadata providers.
 * @since 20.0.0
 */
public typealias RelationType = String

/**
 * Link to a related anime together with the kind of relation.
 * Use [RelatedAnime.of] to create instances from raw data, because it normalizes the label.
 * @since 20.0.0
 * @property uri Link to the related anime on the metadata provider.
 * @property relation Kind of relation as labelled by the metadata provider (see [RelationType]).
 * `null` if the metadata provider doesn't name the relation.
 */
public data class RelatedAnime(
    val uri: URI,
    val relation: RelationType? = null,
) : Comparable<RelatedAnime> {

    override fun compareTo(other: RelatedAnime): Int = compareValuesBy(this, other, { it.uri }, { it.relation })

    override fun toString(): String = if (relation == null) uri.toString() else "$uri ($relation)"

    public companion object {

        /**
         * Creates an instance with a normalized [relation]: line breaks, tabs and special whitespaces are replaced and
         * the label is trimmed. Blank labels result in `null`.
         * @since 20.0.0
         * @param uri Link to the related anime on the metadata provider.
         * @param relation Kind of relation as labelled by the metadata provider.
         * @return New instance.
         */
        public fun of(uri: URI, relation: String? = null): RelatedAnime {
            val normalizedRelation = relation?.normalize()?.trim()?.takeIf { it.neitherNullNorBlank() }
            return RelatedAnime(uri, normalizedRelation)
        }
    }
}
