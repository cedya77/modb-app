package io.github.manamiproject.modb.livechart

import io.github.manamiproject.modb.core.anime.*
import io.github.manamiproject.modb.core.anime.AnimeMedia.NO_PICTURE
import io.github.manamiproject.modb.core.anime.AnimeMedia.NO_PICTURE_THUMBNAIL
import io.github.manamiproject.modb.core.anime.AnimeSeason.Season.*
import io.github.manamiproject.modb.core.anime.AnimeStatus.*
import io.github.manamiproject.modb.core.anime.AnimeType.*
import io.github.manamiproject.modb.core.anime.Duration.TimeUnit.SECONDS
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.converter.AnimeConverter
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_CPU
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.core.extensions.normalize
import io.github.manamiproject.modb.core.extensions.remove
import io.github.manamiproject.modb.core.extractor.DataExtractor
import io.github.manamiproject.modb.core.extractor.ExtractionResult
import io.github.manamiproject.modb.core.extractor.JsonDataExtractor
import io.github.manamiproject.modb.core.extractor.XmlDataExtractor
import kotlinx.coroutines.withContext
import org.apache.commons.text.StringEscapeUtils
import java.net.URI
import io.github.manamiproject.modb.core.anime.AnimeStatus.UNKNOWN as UNKNOWN_STATUS
import io.github.manamiproject.modb.core.anime.AnimeType.UNKNOWN as UNKNOWN_TYPE

/**
 * Converts raw data to an [AnimeRaw].
 * Requires raw HTML.
 * @since 1.0.0
 * @property metaDataProviderConfig Configuration for converting data.
 * @property xmlExtractor Extractor which retrieves the data from raw data.
 * @property jsonExtractor Extractor which retrieves the data from raw data.
 */
public class LivechartAnimeConverter(
    private val metaDataProviderConfig: MetaDataProviderConfig = LivechartConfig,
    private val xmlExtractor: DataExtractor = XmlDataExtractor,
    private val jsonExtractor: DataExtractor = JsonDataExtractor,
): AnimeConverter {

    override suspend fun convert(rawContent: String): AnimeRaw = withContext(LIMITED_CPU) {
        val data = xmlExtractor.extract(rawContent, mapOf(
            "jsonld" to "//script[@type='application/ld+json']/node()",
            "title" to "//meta[@property='og:title']/@content",
            "image" to "//meta[@property='og:image']/@content",
            "episodesDiv" to "//div[text()='Episodes']/../text()",
            "episodesCountdown" to "//div[@data-controller='countdown-bar']//div[contains(text(), 'EP')]/text()",
            "type" to "//div[text()='Format']/..",
            "status" to "//div[text()='Status']/..",
            "duration" to "//div[text()='Run time']/..",
            "season" to "//div[text()='Season']/../a/text()",
            "year" to "//div[text()='Premiere']/following-sibling::*",
            "relatedAnime" to "//div[@data-controller='carousel']//article/a/@href",
            "tags" to "//div[text()='Tags']/..//a[@data-anime-details-target='tagChip']",
            "sourceDiv" to "//div[@data-anime-details-id]/@data-anime-details-id",
            "sourceMeta" to "//meta[@property='og:url']/@content",
            "studios" to "//a[contains(@href, 'studios')]/text()",
            // Layout since the redesign of october 2026: the value sits in the element following its label. The keys
            // above stay as fallback for pages in the earlier layout.
            "typeValue" to "//div[text()='Format']/following-sibling::div[1]/text()",
            "statusValue" to "//div[text()='Status']/following-sibling::*[1]/text()",
            "durationValue" to "//div[text()='Run time']/following-sibling::div[1]/text()",
            "seasonValue" to "//div[text()='Season']/following-sibling::a[1]/text()",
            "yearValue" to "//div[text()='Premiere']/following-sibling::*[1]/text()",
            "episodesDetails" to "//div[@data-anime-details-target='viewingProgression']/@data-viewing-progression-anime-id",
            "episodesTotal" to "//div[@data-anime-details-target='viewingProgression']/@data-viewing-progression-total-count-value",
            "episodesNext" to "//div[a[contains(@href,'/schedules/')]]//strong[starts-with(text(),'EP')]/text()",
            "relatedAnimeCards" to "//article[@data-related-anime-target='anime']//a/@href",
            "tagChips" to "//div[text()='Tags']/following-sibling::div[1]//a/text()",
        ))

        val jsonld = data.listNotNull<String>("jsonld").first()
        val jsonldData = jsonExtractor.extract(jsonld, mapOf(
            "url" to "$.url",
            "genre" to "$.genre",
            "name" to "$.name",
            "image" to "$.image",
            "numberOfEpisodes" to "$.numberOfEpisodes",
            "datePublished" to "$.datePublished",
            "alternateName" to "$.alternateName",
            "score" to "$.aggregateRating.ratingValue",
            "worstRating" to "$.aggregateRating.worstRating",
            "bestRating" to "$.aggregateRating.bestRating",
            "productionCompany" to "$.productionCompany.*.name",
        ))

        val picture = extractPicture(jsonldData, data)

        return@withContext AnimeRaw(
            _title = extractTitle(jsonldData, data),
            episodes = extractEpisodes(jsonldData, data),
            type = extractType(data),
            picture = picture,
            thumbnail = findThumbnail(picture),
            status = extractStatus(data),
            duration = extractDuration(data),
            animeSeason = extractAnimeSeason(data),
            _sources = extractSourcesEntry(jsonldData, data),
            _synonyms = extractSynonyms(jsonldData),
            _relatedAnime = extractRelatedAnime(data),
            _tags = extractTags(jsonldData, data),
            _studios = extractStudios(jsonldData, data),
            _producers = hashSetOf(), // not available on livechart
        ).addScores(extractScore(jsonldData))
    }

    private fun extractTitle(jsonData: ExtractionResult, data: ExtractionResult): Title {
        val name = jsonData.stringOrDefault("name").ifBlank {
            data.stringOrDefault("title")
        }

        return StringEscapeUtils.unescapeHtml4(name)
    }

    private fun extractEpisodes(jsonData: ExtractionResult, data: ExtractionResult): Episodes {
        // JSON data
        var episodes = jsonData.intOrDefault("numberOfEpisodes")
        var isTableValueUnknown = false

        // number of episodes of the details card, layout since october 2026
        if (episodes == 0 && !data.notFound("episodesDetails")) {
            episodes = data.firstText("episodesTotal").toIntOrNull() ?: 0
            isTableValueUnknown = episodes == 0
        }

        // regular value in data table, earlier layout
        if (episodes == 0 && data.notFound("episodesDetails")) {
            val episodesValue = data.stringOrDefault("episodesDiv", "?")
                .trim()
                .split('/')
                .last()

            if (episodesValue != "?" && episodesValue != "-") {
                episodes = episodesValue.toIntOrNull() ?: 0
            } else {
                isTableValueUnknown = true
            }
        }

        // current episode from table if the anime is ongoing
        if (episodes == 0) {
            episodes = data.firstText("episodesNext", "episodesCountdown")
                .remove("EP")
                .trim()
                .toIntOrNull()
                ?: 0

            if (episodes != 0 ) {
                episodes -= 1
            }
        }

        return when {
            episodes == 0 && isTableValueUnknown -> 1
            else -> episodes
        }
    }

    private fun extractType(data: ExtractionResult): AnimeType {
        val value = data.firstText("typeValue", "type")

        return when(value.trim().lowercase()) {
            "movie" -> MOVIE
            "ova" -> OVA
            "special" -> SPECIAL
            "tv" -> TV
            "tv short" -> TV
            "tv special" -> SPECIAL
            "web" -> ONA
            "web short" -> ONA
            "?" -> UNKNOWN_TYPE
            "unknown" -> UNKNOWN_TYPE
            else -> throw IllegalStateException("Unknown type [$value]")
        }
    }

    private fun extractPicture(jsonData: ExtractionResult, data: ExtractionResult): URI {
        val value = jsonData.stringOrDefault("image").ifBlank {
            data.stringOrDefault("image")
        }

        return if (!value.endsWith(LARGE_PICTURE_INDICATOR)) {
            NO_PICTURE
        } else {
            URI(value)
        }
    }

    private fun findThumbnail(uri: URI): URI {
        return if (uri == NO_PICTURE) {
            NO_PICTURE_THUMBNAIL
        } else {
            URI(uri.toString().replace(LARGE_PICTURE_INDICATOR, SMALL_PICTURE_INDICATOR))
        }
    }

    private fun extractStatus(data: ExtractionResult): AnimeStatus {
        val statusString = data.firstText("statusValue", "status")

        return when (statusString.trim().lowercase()){
            "not yet released" -> UPCOMING
            "releasing" -> ONGOING
            "finished" -> FINISHED
            else -> UNKNOWN_STATUS
        }
    }

    private fun extractDuration(data: ExtractionResult): Duration {
        val durationString = data.firstText("durationValue", "duration").trim()

        val seconds = """(\d+ ?[aA-zZ]+)+""".toRegex()
            .findAll(durationString)
            .map { it.value }
            .map {
                val value = ("""\d+""".toRegex().find(it)?.value?.trim() ?: "0").ifBlank { "0" }.toIntOrNull() ?: 0
                val unit = """[a-z]+""".toRegex().find(it)?.value?.trim()?.lowercase() ?: ""
                value to unit
            }
            .map {
                when(it.second) {
                    "hr", "h" -> it.first * 3600
                    "min", "m" -> it.first * 60
                    "sec", "s" -> it.first
                    else -> throw IllegalStateException("Unknown unit [${it.second}]")
                }
            }
            .sum()

        return Duration(seconds, SECONDS)
    }

    private fun extractAnimeSeason(data: ExtractionResult): AnimeSeason {
        val seasonText = data.firstText("seasonValue", "season")
        val splitSeasonString = if (seasonText.isBlank()) {
            listOf(EMPTY)
        } else {
            seasonText.remove("Season ").split(' ')
        }

        val seasonString = splitSeasonString.first().trim().lowercase()
        val season = when(seasonString) {
            "winter" -> WINTER
            "spring" -> SPRING
            "summer" -> SUMMER
            "fall" -> FALL
            else -> UNDEFINED
        }

        val year = if (splitSeasonString.size == 2) {
            YEAR_REGEX.find(splitSeasonString[1])?.value?.trim()?.ifBlank { "0" }?.toIntOrNull() ?: 0
        } else {
            YEAR_REGEX.find(data.firstText("yearValue", "year"))?.value?.trim()?.toIntOrNull() ?: 0
        }

        return AnimeSeason(
            season = season,
            year = year,
        )
    }

    private fun extractSourcesEntry(jsonData: ExtractionResult, data: ExtractionResult): HashSet<URI> {
        if (!data.notFound("sourceDiv")) {
            return hashSetOf(metaDataProviderConfig.buildAnimeLink(data.string("sourceDiv")))
        }

        if (jsonData.notFound("url")) {
            val link = jsonData.string("url").trim().remove("www.").ifBlank {
                data.string("sourceMeta").trim().remove("www.")
            }

            return hashSetOf(URI(link))
        }

        throw IllegalStateException("Unable to extract source.")
    }

    private fun extractSynonyms(jsonData: ExtractionResult): HashSet<Title> {
        return jsonData.listNotNull<Title>("alternateName")
            .map { StringEscapeUtils.unescapeHtml4(it) }
            .toHashSet()
    }

    private fun extractRelatedAnime(data: ExtractionResult): HashSet<URI> {
        val key = listOf("relatedAnimeCards", "relatedAnime").firstOrNull { !data.notFound(it) } ?: return hashSetOf()

        return data.listNotNull<String>(key)
            .mapNotNull { ANIME_LINK.find(it.trim())?.groupValues?.get(1) }
            .map { metaDataProviderConfig.buildAnimeLink(it) }
            .toHashSet()
    }

    private fun extractTags(jsonData: ExtractionResult, data: ExtractionResult): HashSet<Tag> {
        val tags: HashSet<String> = if (jsonData.notFound("genre")) {
            hashSetOf()
        } else {
            jsonData.listNotNull<Tag>("genre")
                .map { it.trim().lowercase() }
                .toHashSet()
        }

        listOf("tagChips", "tags").firstOrNull { !data.notFound(it) }?.let { key ->
            data.listNotNull<Tag>(key)
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .forEach { tags.add(it) }
        }

        return tags
    }

    private fun extractScore(jsonldData: ExtractionResult): MetaDataProviderScore {
        if (jsonldData.notFound("score")) {
            return NoMetaDataProviderScore
        }

        val rawScore = jsonldData.double("score")
        val from = jsonldData.doubleOrDefault("worstRating", 1.0)
        val to = jsonldData.doubleOrDefault("bestRating", 10.0)

        return MetaDataProviderScoreValue(
            hostname = metaDataProviderConfig.hostname(),
            value = rawScore,
            range = from..to,
        )
    }

    private fun extractStudios(jsonData: ExtractionResult, data: ExtractionResult): HashSet<Tag> {
        val studios: HashSet<Studio> = if (jsonData.notFound("productionCompany")) {
            hashSetOf()
        } else {
            jsonData.listNotNull<Studio>("productionCompany").toHashSet()
        }

        if (!data.notFound("studios")) {
            data.listNotNull<Studio>("studios")
                .map { it.normalize().lowercase() }
                .filterNot { it == "studios" || it.isBlank() }
                .forEach { studios.add(it) }
        }

        return studios
    }

    public companion object {
        private const val LARGE_PICTURE_INDICATOR = "large.jpg"
        private const val SMALL_PICTURE_INDICATOR = "small.jpg"
        private val YEAR_REGEX = """\d{4}""".toRegex()
        private val ANIME_LINK = """^(?:https?://(?:www\.)?livechart\.me)?/anime/(\d+)/?$""".toRegex()

        /**
         * Text of the first key which has been found and isn't blank. A key can hold one value or a list of values.
         */
        private fun ExtractionResult.firstText(vararg keys: String): String = keys.asSequence()
            .filterNot { notFound(it) }
            .map { listNotNull<String>(it) { value -> value }.joinToString(" ").trim() }
            .firstOrNull { it.isNotBlank() }
            ?: EMPTY

        /**
         * Singleton of [LivechartAnimeConverter]
         * @since 3.1.0
         */
        public val instance: LivechartAnimeConverter by lazy { LivechartAnimeConverter() }
    }
}