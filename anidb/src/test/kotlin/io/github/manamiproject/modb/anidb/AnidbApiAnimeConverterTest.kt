package io.github.manamiproject.modb.anidb

import io.github.manamiproject.modb.core.anime.AnimeSeason.Season.SPRING
import io.github.manamiproject.modb.core.anime.AnimeStatus.FINISHED
import io.github.manamiproject.modb.core.anime.AnimeType.TV
import io.github.manamiproject.modb.core.anime.Duration
import io.github.manamiproject.modb.core.anime.Duration.TimeUnit.MINUTES
import io.github.manamiproject.modb.test.loadTestResource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset.UTC
import kotlin.test.Test

internal class AnidbApiAnimeConverterTest {

    @Nested
    inner class ConvertTests {

        @Test
        fun `converts a real api response`() {
            runBlocking {
                // given
                val testFile = loadTestResource<String>("AnidbApiAnimeConverterTest/cowboy-bebop.xml")
                val converter = AnidbApiAnimeConverter(
                    clock = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), UTC),
                )

                // when
                val result = converter.convert(testFile)

                // then
                assertThat(result.title).isEqualTo("Cowboy Bebop")
                assertThat(result.type).isEqualTo(TV)
                assertThat(result.episodes).isEqualTo(26)
                assertThat(result.status).isEqualTo(FINISHED)
                assertThat(result.animeSeason.season).isEqualTo(SPRING)
                assertThat(result.animeSeason.year).isEqualTo(1998)
                assertThat(result.duration).isEqualTo(Duration(25, MINUTES))
                assertThat(result.sources).containsExactly(URI("https://anidb.net/anime/23"))
                assertThat(result.picture).isEqualTo(URI("https://cdn.anidb.net/images/main/221595.jpg"))
                assertThat(result.thumbnail).isEqualTo(URI("https://cdn.anidb.net/images/main/221595.jpg-thumb.jpg"))
                assertThat(result.relatedAnime).containsExactly(URI("https://anidb.net/anime/219"))
                assertThat(result.synonyms).contains("Kaubojus Bibopas")
                assertThat(result.tags).contains("bounty hunter", "detective")
            }
        }
    }
}
