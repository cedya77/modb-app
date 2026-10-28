package io.github.manamiproject.modb.core.json

import io.github.manamiproject.modb.core.anime.RelatedAnime
import io.github.manamiproject.modb.test.exceptionExpected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import java.net.URI
import kotlin.test.Test

internal class RelatedAnimeAdapterTest {

    @Nested
    inner class FromJsonTests {

        @Test
        fun `correctly deserialize object with uri and relation`() {
            // given
            val adapter = RelatedAnimeAdapter()

            // when
            val result = adapter.fromJson("""{"uri":"https://myanimelist.net/anime/2994","relation":"Summary"}""")

            // then
            assertThat(result).isEqualTo(RelatedAnime(URI("https://myanimelist.net/anime/2994"), "Summary"))
        }

        @Test
        fun `correctly deserialize object without relation`() {
            // given
            val adapter = RelatedAnimeAdapter()

            // when
            val result = adapter.fromJson("""{"uri":"https://livechart.me/anime/3808"}""")

            // then
            assertThat(result).isEqualTo(RelatedAnime(URI("https://livechart.me/anime/3808")))
        }

        @Test
        fun `correctly deserialize object with relation null`() {
            // given
            val adapter = RelatedAnimeAdapter()

            // when
            val result = adapter.fromJson("""{"uri":"https://livechart.me/anime/3808","relation":null}""")

            // then
            assertThat(result).isEqualTo(RelatedAnime(URI("https://livechart.me/anime/3808")))
        }

        @Test
        fun `blank relation results in null`() {
            // given
            val adapter = RelatedAnimeAdapter()

            // when
            val result = adapter.fromJson("""{"uri":"https://livechart.me/anime/3808","relation":"  "}""")

            // then
            assertThat(result).isEqualTo(RelatedAnime(URI("https://livechart.me/anime/3808")))
        }

        @Test
        fun `reads the previous format which only contains the link`() {
            // given
            val adapter = RelatedAnimeAdapter()

            // when
            val result = adapter.fromJson("\"https://myanimelist.net/anime/2994\"")

            // then
            assertThat(result).isEqualTo(RelatedAnime(URI("https://myanimelist.net/anime/2994")))
        }

        @Test
        fun `ignores unknown properties`() {
            // given
            val adapter = RelatedAnimeAdapter()

            // when
            val result = adapter.fromJson("""{"uri":"https://myanimelist.net/anime/2994","other":1,"relation":"Summary"}""")

            // then
            assertThat(result).isEqualTo(RelatedAnime(URI("https://myanimelist.net/anime/2994"), "Summary"))
        }

        @Test
        fun `throws exception if uri is missing`() {
            // given
            val adapter = RelatedAnimeAdapter()

            // when
            val result = exceptionExpected<IllegalStateException> {
                adapter.fromJson("""{"relation":"Summary"}""")
            }

            // then
            assertThat(result).hasMessage("Property 'uri' is either missing or null.")
        }
    }

    @Nested
    inner class ToJsonTests {

        @Test
        fun `correctly serialize uri and relation`() {
            // given
            val adapter = RelatedAnimeAdapter()

            // when
            val result = adapter.toJson(RelatedAnime(URI("https://myanimelist.net/anime/2994"), "Summary"))

            // then
            assertThat(result).isEqualTo("""{"uri":"https://myanimelist.net/anime/2994","relation":"Summary"}""")
        }

        @Test
        fun `omits relation if it is unknown`() {
            // given
            val adapter = RelatedAnimeAdapter()

            // when
            val result = adapter.toJson(RelatedAnime(URI("https://livechart.me/anime/3808")))

            // then
            assertThat(result).isEqualTo("""{"uri":"https://livechart.me/anime/3808"}""")
        }

        @Test
        fun `omits relation if it is unknown even if serializeNull is set`() {
            // given
            val adapter = RelatedAnimeAdapter().serializeNulls()

            // when
            val result = adapter.toJson(RelatedAnime(URI("https://livechart.me/anime/3808")))

            // then
            assertThat(result).isEqualTo("""{"uri":"https://livechart.me/anime/3808"}""")
        }

        @Test
        fun `throws exception if value is null`() {
            // given
            val adapter = RelatedAnimeAdapter()

            // when
            val result = exceptionExpected<IllegalArgumentException> {
                adapter.toJson(null)
            }

            // then
            assertThat(result).hasMessage("RelatedAnimeAdapter expects non-nullable value, but received null.")
        }
    }
}
