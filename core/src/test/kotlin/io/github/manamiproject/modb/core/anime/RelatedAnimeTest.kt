package io.github.manamiproject.modb.core.anime

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import kotlin.test.Test

internal class RelatedAnimeTest {

    @Nested
    inner class OfTests {

        @Test
        fun `keeps the label as the metadata provider writes it`() {
            // when
            val result = RelatedAnime.of(URI("https://myanimelist.net/anime/2994"), "Side Story")

            // then
            assertThat(result).isEqualTo(RelatedAnime(URI("https://myanimelist.net/anime/2994"), "Side Story"))
        }

        @Test
        fun `trims the label and normalizes whitespaces`() {
            // when
            val result = RelatedAnime.of(URI("https://anidb.net/anime/405"), "\n\t Side story \n")

            // then
            assertThat(result.relation).isEqualTo("Side story")
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "   ", "\n", "\t"])
        fun `blank label results in null`(value: String) {
            // when
            val result = RelatedAnime.of(URI("https://anidb.net/anime/405"), value)

            // then
            assertThat(result.relation).isNull()
        }

        @Test
        fun `null label results in null`() {
            // when
            val result = RelatedAnime.of(URI("https://anidb.net/anime/405"), null)

            // then
            assertThat(result.relation).isNull()
        }
    }

    @Nested
    inner class CompareToTests {

        @Test
        fun `sorts by link first and by relation second`() {
            // given
            val list = listOf(
                RelatedAnime(URI("https://myanimelist.net/anime/2"), "Sequel"),
                RelatedAnime(URI("https://myanimelist.net/anime/1"), "Sequel"),
                RelatedAnime(URI("https://myanimelist.net/anime/1")),
            )

            // when
            val result = list.sorted()

            // then
            assertThat(result).containsExactly(
                RelatedAnime(URI("https://myanimelist.net/anime/1")),
                RelatedAnime(URI("https://myanimelist.net/anime/1"), "Sequel"),
                RelatedAnime(URI("https://myanimelist.net/anime/2"), "Sequel"),
            )
        }
    }

    @Nested
    inner class ToStringTests {

        @Test
        fun `only the link if there is no relation`() {
            // when
            val result = RelatedAnime(URI("https://myanimelist.net/anime/2994")).toString()

            // then
            assertThat(result).isEqualTo("https://myanimelist.net/anime/2994")
        }

        @Test
        fun `link and relation in brackets`() {
            // when
            val result = RelatedAnime(URI("https://myanimelist.net/anime/2994"), "Summary").toString()

            // then
            assertThat(result).isEqualTo("https://myanimelist.net/anime/2994 (Summary)")
        }
    }
}
