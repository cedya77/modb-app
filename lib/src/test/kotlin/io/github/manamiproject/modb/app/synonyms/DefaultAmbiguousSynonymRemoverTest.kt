package io.github.manamiproject.modb.app.synonyms

import io.github.manamiproject.modb.core.anime.RelatedAnime
import io.github.manamiproject.modb.core.anime.AnimeRaw
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import java.net.URI
import kotlin.test.Test

internal class DefaultAmbiguousSynonymRemoverTest {

    @Nested
    inner class RemoveSynonymsWhichNameARelatedAnimeTests {

        @Test
        fun `removes the plain franchise name from a sequel`() {
            // given
            val season1 = AnimeRaw(
                _title = "Clevatess: Majuu no Ou to Akago to Shikabane no Yuusha",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf("Clevatess"),
                _relatedAnime = hashSetOf(RelatedAnime(URI("http://example.org/anime/2"))),
            )
            val season2 = AnimeRaw(
                _title = "Clevatess II: Majuu no Ou to Itsuwari no Yuusha Denshou",
                _sources = hashSetOf(URI("http://example.org/anime/2")),
                _synonyms = hashSetOf("Clevatess", "Clevatess Season 2"),
                _relatedAnime = hashSetOf(RelatedAnime(URI("http://example.org/anime/1"))),
            )

            // when
            DefaultAmbiguousSynonymRemover().removeSynonymsWhichNameARelatedAnime(listOf(season1, season2))

            // then
            assertThat(season2.synonyms).containsExactly("Clevatess Season 2")
            assertThat(season1.synonyms).containsExactly("Clevatess")
        }

        @Test
        fun `removes the plain name in every language in which the sequel is qualified`() {
            // given
            val season1 = AnimeRaw(
                _title = "Jibaku Shounen Hanako-kun",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf("Toilet-bound Hanako-kun", "地縛少年花子くん"),
                _relatedAnime = hashSetOf(RelatedAnime(URI("http://example.org/anime/2"))),
            )
            val season2 = AnimeRaw(
                _title = "Jibaku Shounen Hanako-kun 2",
                _sources = hashSetOf(URI("http://example.org/anime/2")),
                _synonyms = hashSetOf(
                    "Toilet-bound Hanako-kun",
                    "Toilet-bound Hanako-kun 2nd Season",
                    "地縛少年花子くん",
                    "地縛少年花子くん 第2期",
                ),
                _relatedAnime = hashSetOf(RelatedAnime(URI("http://example.org/anime/1"))),
            )

            // when
            DefaultAmbiguousSynonymRemover().removeSynonymsWhichNameARelatedAnime(listOf(season1, season2))

            // then
            assertThat(season2.synonyms).containsExactlyInAnyOrder(
                "Toilet-bound Hanako-kun 2nd Season",
                "地縛少年花子くん 第2期",
            )
        }

        @Test
        fun `doesn't remove a synonym which is not shared with a related anime`() {
            // given
            val anime = AnimeRaw(
                _title = "Monster: The Perfect Edition",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf("Monster", "Monster 2"),
                _relatedAnime = hashSetOf(RelatedAnime(URI("http://example.org/anime/2"))),
            )
            val unrelatedName = AnimeRaw(
                _title = "Naoki Urasawa",
                _sources = hashSetOf(URI("http://example.org/anime/2")),
                _relatedAnime = hashSetOf(RelatedAnime(URI("http://example.org/anime/1"))),
            )

            // when
            DefaultAmbiguousSynonymRemover().removeSynonymsWhichNameARelatedAnime(listOf(anime, unrelatedName))

            // then
            assertThat(anime.synonyms).containsExactlyInAnyOrder("Monster", "Monster 2")
        }

        @Test
        fun `doesn't remove a synonym which the entry doesn't qualify itself`() {
            // given
            val first = AnimeRaw(
                _title = "Kikou Kantai Dairugger XV",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf("Voltron"),
                _relatedAnime = hashSetOf(RelatedAnime(URI("http://example.org/anime/2"))),
            )
            val second = AnimeRaw(
                _title = "Hyakujuu-Ou GoLion",
                _sources = hashSetOf(URI("http://example.org/anime/2")),
                _synonyms = hashSetOf("Voltron"),
                _relatedAnime = hashSetOf(RelatedAnime(URI("http://example.org/anime/1"))),
            )

            // when
            DefaultAmbiguousSynonymRemover().removeSynonymsWhichNameARelatedAnime(listOf(first, second))

            // then
            assertThat(first.synonyms).containsExactly("Voltron")
            assertThat(second.synonyms).containsExactly("Voltron")
        }

        @Test
        fun `treats a year as a disambiguator and not as a position in a sequence`() {
            // given
            val original = AnimeRaw(
                _title = "Hunter x Hunter",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf("Hunter x Hunter (1999)"),
                _relatedAnime = hashSetOf(RelatedAnime(URI("http://example.org/anime/2"))),
            )
            val remake = AnimeRaw(
                _title = "Hunter x Hunter (2011)",
                _sources = hashSetOf(URI("http://example.org/anime/2")),
                _synonyms = hashSetOf("Hunter x Hunter"),
                _relatedAnime = hashSetOf(RelatedAnime(URI("http://example.org/anime/1"))),
            )

            // when
            DefaultAmbiguousSynonymRemover().removeSynonymsWhichNameARelatedAnime(listOf(original, remake))

            // then
            assertThat(remake.synonyms).containsExactly("Hunter x Hunter")
        }

        @Test
        fun `doesn't remove a synonym when both entries qualify it`() {
            // given
            val season1 = AnimeRaw(
                _title = "Ashita no Joe",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf("Rocky Joe", "Rocky Joe 1"),
                _relatedAnime = hashSetOf(RelatedAnime(URI("http://example.org/anime/2"))),
            )
            val season2 = AnimeRaw(
                _title = "Ashita no Joe 2",
                _sources = hashSetOf(URI("http://example.org/anime/2")),
                _synonyms = hashSetOf("Rocky Joe", "Rocky Joe 2"),
                _relatedAnime = hashSetOf(RelatedAnime(URI("http://example.org/anime/1"))),
            )

            // when
            DefaultAmbiguousSynonymRemover().removeSynonymsWhichNameARelatedAnime(listOf(season1, season2))

            // then
            assertThat(season1.synonyms).containsExactlyInAnyOrder("Rocky Joe", "Rocky Joe 1")
            assertThat(season2.synonyms).containsExactlyInAnyOrder("Rocky Joe", "Rocky Joe 2")
        }

        @Test
        fun `doesn't remove anything from an entry without related anime`() {
            // given
            val anime = AnimeRaw(
                _title = "Clevatess II",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf("Clevatess", "Clevatess Season 2"),
            )

            // when
            DefaultAmbiguousSynonymRemover().removeSynonymsWhichNameARelatedAnime(listOf(anime))

            // then
            assertThat(anime.synonyms).containsExactlyInAnyOrder("Clevatess", "Clevatess Season 2")
        }

        @Test
        fun `ignores related anime which have no entry of their own`() {
            // given
            val anime = AnimeRaw(
                _title = "Clevatess II",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf("Clevatess", "Clevatess Season 2"),
                _relatedAnime = hashSetOf(RelatedAnime(URI("http://example.org/anime/unknown"))),
            )

            // when
            DefaultAmbiguousSynonymRemover().removeSynonymsWhichNameARelatedAnime(listOf(anime))

            // then
            assertThat(anime.synonyms).containsExactlyInAnyOrder("Clevatess", "Clevatess Season 2")
        }

        @Test
        fun `recognizes a fullwidth digit as a position in a sequence`() {
            // given
            val season1 = AnimeRaw(
                _title = "Kyouryuu Shoujo Gauko",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf("\u6050\u7adc\u5c11\u5973\u30ac\u30a6\u5b50"),
                _relatedAnime = hashSetOf(RelatedAnime(URI("http://example.org/anime/2"))),
            )
            val season2 = AnimeRaw(
                _title = "Kyouryuu Shoujo Gauko 2nd Season",
                _sources = hashSetOf(URI("http://example.org/anime/2")),
                _synonyms = hashSetOf("\u6050\u7adc\u5c11\u5973\u30ac\u30a6\u5b50", "\u6050\u7adc\u5c11\u5973\u30ac\u30a6\u5b50 \uff12"),
                _relatedAnime = hashSetOf(RelatedAnime(URI("http://example.org/anime/1"))),
            )

            // when
            DefaultAmbiguousSynonymRemover().removeSynonymsWhichNameARelatedAnime(listOf(season1, season2))

            // then
            assertThat(season2.synonyms).containsExactly("\u6050\u7adc\u5c11\u5973\u30ac\u30a6\u5b50 \uff12")
        }

        @Test
        fun `treats an ideographic space like any other whitespace`() {
            // given
            val original = AnimeRaw(
                _title = "Sengoku Youko",
                _sources = hashSetOf(URI("http://example.org/anime/1")),
                _synonyms = hashSetOf("\u6226\u56fd\u5996\u72d0 \u4e16\u76f4\u3057\u59c9\u5f1f\u7de8"),
                _relatedAnime = hashSetOf(RelatedAnime(URI("http://example.org/anime/2"))),
            )
            val recap = AnimeRaw(
                _title = "Sengoku Youko: Yonaoshi Kyoudai-hen Recap",
                _sources = hashSetOf(URI("http://example.org/anime/2")),
                _synonyms = hashSetOf(
                    "\u6226\u56fd\u5996\u72d0\u3000\u4e16\u76f4\u3057\u59c9\u5f1f\u7de8",
                    "\u6226\u56fd\u5996\u72d0\u3000\u4e16\u76f4\u3057\u59c9\u5f1f\u7de8 \u7b2c2\u671f",
                ),
                _relatedAnime = hashSetOf(RelatedAnime(URI("http://example.org/anime/1"))),
            )

            // when
            DefaultAmbiguousSynonymRemover().removeSynonymsWhichNameARelatedAnime(listOf(original, recap))

            // then
            assertThat(recap.synonyms).containsExactly("\u6226\u56fd\u5996\u72d0\u3000\u4e16\u76f4\u3057\u59c9\u5f1f\u7de8 \u7b2c2\u671f")
        }

        @Test
        fun `returns the same list`() {
            // given
            val animeList = listOf(
                AnimeRaw(
                    _title = "Clevatess",
                    _sources = hashSetOf(URI("http://example.org/anime/1")),
                ),
            )

            // when
            val result = DefaultAmbiguousSynonymRemover().removeSynonymsWhichNameARelatedAnime(animeList)

            // then
            assertThat(result).isEqualTo(animeList)
        }
    }

    @Nested
    inner class CompanionObjectTests {

        @Test
        fun `instance property always returns same instance`() {
            // given
            val previous = DefaultAmbiguousSynonymRemover.instance

            // when
            val result = DefaultAmbiguousSynonymRemover.instance

            // then
            assertThat(result).isExactlyInstanceOf(DefaultAmbiguousSynonymRemover::class.java)
            assertThat(result === previous).isTrue()
        }
    }
}
