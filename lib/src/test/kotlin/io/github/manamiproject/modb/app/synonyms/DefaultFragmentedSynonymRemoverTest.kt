package io.github.manamiproject.modb.app.synonyms

import io.github.manamiproject.modb.core.anime.AnimeRaw
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import java.net.URI
import kotlin.test.Test

internal class DefaultFragmentedSynonymRemoverTest {

    @Nested
    inner class RemoveSynonymsWhichAreAFragmentOfAnotherNameTests {

        @Test
        fun `removes a fragment which starts in lower case`() {
            // given
            val anime = AnimeRaw(
                _title = "Mahou Shoujo ni Akogarete",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf(
                    "I Admire Magical Girls",
                    "I Admire Magical Girls, and...",
                    "and...",
                ),
            )

            // when
            DefaultFragmentedSynonymRemover().removeSynonymsWhichAreAFragmentOfAnotherName(listOf(anime))

            // then
            assertThat(anime.synonyms).containsExactlyInAnyOrder(
                "I Admire Magical Girls",
                "I Admire Magical Girls, and...",
            )
        }

        @Test
        fun `removes every fragment of a name which contains multiple commas`() {
            // given
            val anime = AnimeRaw(
                _title = "Ame to Shoujo to Watashi no Tegami",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf(
                    "Rain, the Little Girl, and My Letter",
                    "the Little Girl",
                    "and My Letter",
                ),
            )

            // when
            DefaultFragmentedSynonymRemover().removeSynonymsWhichAreAFragmentOfAnotherName(listOf(anime))

            // then
            assertThat(anime.synonyms).containsExactly("Rain, the Little Girl, and My Letter")
        }

        @Test
        fun `matches an ellipsis against three dots`() {
            // given
            val anime = AnimeRaw(
                _title = "Mahou Shoujo ni Akogarete 2nd Season",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf(
                    "I Admire Magical Girls, and… 2",
                    "and... 2",
                ),
            )

            // when
            DefaultFragmentedSynonymRemover().removeSynonymsWhichAreAFragmentOfAnotherName(listOf(anime))

            // then
            assertThat(anime.synonyms).containsExactly("I Admire Magical Girls, and… 2")
        }

        @Test
        fun `removes a number which has been cut at its thousands separator`() {
            // given
            val anime = AnimeRaw(
                _title = "100-man no Inochi no Ue ni Ore wa Tatteiru",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf(
                    "I'm Standing on 1,000,000 Lives.",
                    "000",
                ),
            )

            // when
            DefaultFragmentedSynonymRemover().removeSynonymsWhichAreAFragmentOfAnotherName(listOf(anime))

            // then
            assertThat(anime.synonyms).containsExactly("I'm Standing on 1,000,000 Lives.")
        }

        @Test
        fun `removes a japanese fragment of a name which uses a latin comma`() {
            // given
            val anime = AnimeRaw(
                _title = "Akai Shouzou: Char, Soshite Frontal e",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf(
                    "赤い肖像 ~シャア, そしてフロンタルへ~",
                    "そしてフロンタルへ~",
                ),
            )

            // when
            DefaultFragmentedSynonymRemover().removeSynonymsWhichAreAFragmentOfAnotherName(listOf(anime))

            // then
            assertThat(anime.synonyms).containsExactly("赤い肖像 ~シャア, そしてフロンタルへ~")
        }

        @Test
        fun `removes a fragment of the title`() {
            // given
            val anime = AnimeRaw(
                _title = "Dooly, the Baby Dinosaur (2008)",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf(
                    "Agigongryong Doolie",
                    "the Baby Dinosaur (2008)",
                ),
            )

            // when
            DefaultFragmentedSynonymRemover().removeSynonymsWhichAreAFragmentOfAnotherName(listOf(anime))

            // then
            assertThat(anime.synonyms).containsExactly("Agigongryong Doolie")
        }

        @Test
        fun `doesn't remove the piece before the first comma`() {
            // given
            val anime = AnimeRaw(
                _title = "Gintama: Shinyaku Benizakura-hen",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf(
                    "Gintama, the Movie",
                    "Gintama",
                ),
            )

            // when
            DefaultFragmentedSynonymRemover().removeSynonymsWhichAreAFragmentOfAnotherName(listOf(anime))

            // then
            assertThat(anime.synonyms).containsExactlyInAnyOrder(
                "Gintama, the Movie",
                "Gintama",
            )
        }

        @Test
        fun `doesn't remove a piece after a comma which looks like a name on its own`() {
            // given
            val anime = AnimeRaw(
                _title = "Natsu e no Tunnel, Sayonara no Deguchi",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf(
                    "The Tunnel to Summer, the Exit of Goodbyes",
                    "Sayonara no Deguchi",
                ),
            )

            // when
            DefaultFragmentedSynonymRemover().removeSynonymsWhichAreAFragmentOfAnotherName(listOf(anime))

            // then
            assertThat(anime.synonyms).containsExactlyInAnyOrder(
                "The Tunnel to Summer, the Exit of Goodbyes",
                "Sayonara no Deguchi",
            )
        }

        @Test
        fun `doesn't remove a synonym which looks like a fragment, but isn't a piece of another name`() {
            // given
            val anime = AnimeRaw(
                _title = "Kaguya-sama wa Kokurasetai: Tensai-tachi no Renai Zunousen",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf(
                    "Kaguya-sama: Love Is War",
                    "かぐや様は告らせたい",
                    "kaguya",
                ),
            )

            // when
            DefaultFragmentedSynonymRemover().removeSynonymsWhichAreAFragmentOfAnotherName(listOf(anime))

            // then
            assertThat(anime.synonyms).containsExactlyInAnyOrder(
                "Kaguya-sama: Love Is War",
                "かぐや様は告らせたい",
                "kaguya",
            )
        }

        @Test
        fun `doesn't remove a fragment of a name of a different entry`() {
            // given
            val anime1 = AnimeRaw(
                _title = "Ame to Shoujo to Watashi no Tegami",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf("Rain, the Little Girl, and My Letter"),
            )
            val anime2 = AnimeRaw(
                _title = "Madogiwa no Totto-chan",
                _sources = hashSetOf(URI("http://example.org/anime/2")),
                _synonyms = hashSetOf("the Little Girl"),
            )

            // when
            DefaultFragmentedSynonymRemover().removeSynonymsWhichAreAFragmentOfAnotherName(listOf(anime1, anime2))

            // then
            assertThat(anime2.synonyms).containsExactly("the Little Girl")
        }

        @Test
        fun `returns the same list`() {
            // given
            val animeList = listOf(
                AnimeRaw(
                    _title = "Mahou Shoujo ni Akogarete",
                    _sources = hashSetOf(URI("http://example.org/anime/1")),
                ),
            )

            // when
            val result = DefaultFragmentedSynonymRemover().removeSynonymsWhichAreAFragmentOfAnotherName(animeList)

            // then
            assertThat(result).isEqualTo(animeList)
        }
    }

    @Nested
    inner class CompanionObjectTests {

        @Test
        fun `instance property always returns same instance`() {
            // given
            val previous = DefaultFragmentedSynonymRemover.instance

            // when
            val result = DefaultFragmentedSynonymRemover.instance

            // then
            assertThat(result).isExactlyInstanceOf(DefaultFragmentedSynonymRemover::class.java)
            assertThat(result === previous).isTrue()
        }
    }
}
