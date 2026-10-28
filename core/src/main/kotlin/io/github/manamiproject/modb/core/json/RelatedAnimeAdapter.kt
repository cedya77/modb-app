package io.github.manamiproject.modb.core.json

import com.squareup.moshi.*
import com.squareup.moshi.JsonReader.Token.NULL
import com.squareup.moshi.JsonReader.Token.STRING
import io.github.manamiproject.modb.core.anime.RelatedAnime
import java.net.URI

/**
 * Reads and writes [RelatedAnime] as `{"uri": "...", "relation": "..."}`. `relation` is omitted if it is unknown.
 * For compatibility with files written before relation types existed, a plain string is read as a link without
 * [RelatedAnime.relation].
 */
internal class RelatedAnimeAdapter: JsonAdapter<RelatedAnime>() {

    @FromJson
    override fun fromJson(reader: JsonReader): RelatedAnime {
        if (reader.peek() == STRING) {
            return RelatedAnime(URI(reader.nextString()))
        }

        reader.beginObject()

        var uri: URI? = null
        var relation: String? = null

        while (reader.hasNext()) {
            when (reader.nextName()) {
                "uri" -> uri = URI(reader.nextString())
                "relation" -> relation = if (reader.peek() != NULL) { reader.nextString() } else { reader.nextNull<Unit>(); null }
                else -> reader.skipValue()
            }
        }

        reader.endObject()

        if (uri == null) {
            throw IllegalStateException("Property 'uri' is either missing or null.")
        }

        return RelatedAnime.of(uri, relation)
    }

    @ToJson
    override fun toJson(writer: JsonWriter, value: RelatedAnime?) {
        requireNotNull(value) { "RelatedAnimeAdapter expects non-nullable value, but received null." }

        writer.beginObject()
        writer.name("uri").value(value.uri.toString())

        // "relation" is left out if the metadata provider doesn't name the relation, regardless of serializing nulls
        value.relation?.let { writer.name("relation").value(it) }

        writer.endObject()
    }
}
