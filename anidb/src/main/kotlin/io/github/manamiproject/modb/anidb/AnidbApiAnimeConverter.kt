package io.github.manamiproject.modb.anidb

import io.github.manamiproject.modb.core.anime.*
import io.github.manamiproject.modb.core.anime.AnimeMedia.NO_PICTURE
import io.github.manamiproject.modb.core.anime.AnimeMedia.NO_PICTURE_THUMBNAIL
import io.github.manamiproject.modb.core.anime.AnimeSeason.Season.*
import io.github.manamiproject.modb.core.anime.AnimeStatus.*
import io.github.manamiproject.modb.core.anime.AnimeStatus.UNKNOWN as UNKNOWN_STATUS
import io.github.manamiproject.modb.core.anime.AnimeType.*
import io.github.manamiproject.modb.core.anime.AnimeType.UNKNOWN as UNKNOWN_TYPE
import io.github.manamiproject.modb.core.anime.Duration.Companion.UNKNOWN as UNKNOWN_DURATION
import io.github.manamiproject.modb.core.anime.Duration.TimeUnit.MINUTES
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.converter.AnimeConverter
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_CPU
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.core.extensions.neitherNullNorBlank
import io.github.manamiproject.modb.core.extractor.DataExtractor
import io.github.manamiproject.modb.core.extractor.ExtractionResult
import io.github.manamiproject.modb.core.extractor.XmlDataExtractor
import kotlinx.coroutines.withContext
import java.net.URI
import java.time.Clock
import java.time.LocalDate

/**
 * Converts a response of the AniDB HTTP API into an [AnimeRaw].
 *
 * The API returns as structured data what [AnidbAnimeConverter] has to recover from the layout of a
 * web page, so titles, tags and relations are read directly rather than inferred.
 * @since 1.0.0
 * @property metaDataProviderConfig Configuration for converting data.
 * @property extractor Extractor which retrieves the data from raw data.
 * @property clock Used to determine the current date.
 */
public class AnidbApiAnimeConverter(
    private val metaDataProviderConfig: MetaDataProviderConfig = AnidbApiConfig,
    private val extractor: DataExtractor = XmlDataExtractor,
    private val clock: Clock = Clock.systemUTC(),
) : AnimeConverter {

    override suspend fun convert(rawContent: String): AnimeRaw = withContext(LIMITED_CPU) {
        val data = extractor.extract(rawContent, mapOf(
            "id" to "//anime[@restricted]/@id",
            "type" to "//anime/type/text()",
            "episodecount" to "//anime/episodecount/text()",
            "startdate" to "//anime/startdate/text()",
            "enddate" to "//anime/enddate/text()",
            "picture" to "//anime/picture/text()",
            "mainTitle" to "//anime/titles/title[@type='main']/text()",
            "otherTitles" to "//anime/titles/title[@type!='main']/text()",
            "tags" to "//anime/tags/tag/name/text()",
            "relatedAnime" to "//anime/relatedanime/anime/@id",
            "episodeLengths" to "//anime/episodes/episode/length/text()",
            "score" to "//anime/ratings/permanent/text()",
        ))

        val picture = extractPicture(data)
        val startDate = parseDate(data.stringOrDefault("startdate"))
        val endDate = parseDate(data.stringOrDefault("enddate"))

        return@withContext AnimeRaw(
            _title = data.string("mainTitle").trim(),
            episodes = extractEpisodes(data),
            type = extractType(data),
            picture = picture,
            thumbnail = extractThumbnail(picture),
            status = extractStatus(startDate, endDate),
            duration = extractDuration(data),
            animeSeason = extractAnimeSeason(startDate),
            _sources = hashSetOf(metaDataProviderConfig.buildAnimeLink(data.string("id").trim())),
            _synonyms = extractSynonyms(data),
            _relatedAnime = extractRelatedAnime(data),
            _tags = extractTags(data),
            _studios = hashSetOf(),
            _producers = hashSetOf(),
        ).addScores(extractScore(data))
    }

    private fun extractEpisodes(data: ExtractionResult): Int {
        return data.stringOrDefault("episodecount").trim().toIntOrNull() ?: 0
    }

    private fun extractType(data: ExtractionResult): AnimeType {
        return when (val type = data.stringOrDefault("type").trim().lowercase()) {
            "tv series" -> TV
            "movie" -> MOVIE
            "ova" -> OVA
            "web" -> ONA
            "tv special", "music video", "other" -> SPECIAL
            EMPTY, "unknown" -> UNKNOWN_TYPE
            else -> throw IllegalStateException("Unknown type [$type]")
        }
    }

    private fun extractPicture(data: ExtractionResult): URI {
        val file = data.stringOrDefault("picture").trim()

        return when {
            file.neitherNullNorBlank() -> URI("$CDN/images/main/$file")
            else -> NO_PICTURE
        }
    }

    private fun extractThumbnail(picture: URI): URI = when (picture) {
        NO_PICTURE -> NO_PICTURE_THUMBNAIL
        else -> URI("$picture-thumb.jpg")
    }

    private fun extractStatus(startDate: LocalDate?, endDate: LocalDate?): AnimeStatus {
        val today = LocalDate.now(clock)

        return when {
            startDate == null -> UNKNOWN_STATUS
            startDate.isAfter(today) -> UPCOMING
            endDate != null && endDate.isBefore(today) -> FINISHED
            endDate == null -> ONGOING
            else -> ONGOING
        }
    }

    private fun extractDuration(data: ExtractionResult): Duration {
        if (data.notFound("episodeLengths")) {
            return UNKNOWN_DURATION
        }

        // Episode lengths vary within a series, so the one which occurs most often describes it best.
        val minutes = data.listNotNull<String>("episodeLengths")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        return when (minutes) {
            null -> UNKNOWN_DURATION
            else -> Duration(minutes, MINUTES)
        }
    }

    private fun extractAnimeSeason(startDate: LocalDate?): AnimeSeason {
        if (startDate == null) {
            return AnimeSeason()
        }

        val season = when (startDate.month.value) {
            1, 2, 3 -> WINTER
            4, 5, 6 -> SPRING
            7, 8, 9 -> SUMMER
            10, 11, 12 -> FALL
            else -> UNDEFINED
        }

        return AnimeSeason(
            season = season,
            year = startDate.year,
        )
    }

    private fun extractSynonyms(data: ExtractionResult): HashSet<Title> {
        if (data.notFound("otherTitles")) {
            return hashSetOf()
        }

        return data.listNotNull<Title>("otherTitles")
            .map { it.trim() }
            .filter { it.neitherNullNorBlank() }
            .toHashSet()
    }

    private fun extractRelatedAnime(data: ExtractionResult): HashSet<URI> {
        if (data.notFound("relatedAnime")) {
            return hashSetOf()
        }

        return data.listNotNull<String>("relatedAnime")
            .map { metaDataProviderConfig.buildAnimeLink(it.trim()) }
            .toHashSet()
    }

    private fun extractTags(data: ExtractionResult): HashSet<Tag> {
        if (data.notFound("tags")) {
            return hashSetOf()
        }

        return data.listNotNull<Tag>("tags")
            .map { it.trim().lowercase() }
            .filter { it.neitherNullNorBlank() }
            .toHashSet()
    }

    private fun extractScore(data: ExtractionResult): MetaDataProviderScore {
        if (data.notFound("score")) {
            return NoMetaDataProviderScore
        }

        val value = data.stringOrDefault("score").trim().toDoubleOrNull() ?: return NoMetaDataProviderScore

        return MetaDataProviderScoreValue(
            hostname = metaDataProviderConfig.hostname(),
            value = value,
            range = 1.0..10.0,
        )
    }

    private fun parseDate(value: String): LocalDate? {
        val match = DATEFORMAT.find(value.trim()) ?: return null

        return LocalDate.of(
            match.groups["year"]!!.value.toInt(),
            match.groups["month"]!!.value.toInt(),
            match.groups["day"]!!.value.toInt(),
        )
    }

    public companion object {
        private const val CDN = "https://cdn.anidb.net"
        private val DATEFORMAT = """(?<year>\d{4})-(?<month>\d{2})-(?<day>\d{2})""".toRegex()

        /**
         * Singleton of [AnidbApiAnimeConverter]
         * @since 1.0.0
         */
        public val instance: AnidbApiAnimeConverter by lazy { AnidbApiAnimeConverter() }
    }
}
