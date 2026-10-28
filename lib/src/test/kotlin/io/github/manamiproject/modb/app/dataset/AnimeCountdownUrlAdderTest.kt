package io.github.manamiproject.modb.app.dataset

import io.github.manamiproject.modb.anilist.AnilistConfig
import io.github.manamiproject.modb.animecountdown.AnimeCountdownConfig
import io.github.manamiproject.modb.core.anime.AnimeRaw
import io.github.manamiproject.modb.core.anime.RelatedAnime
import io.github.manamiproject.modb.myanimelist.MyanimelistConfig
import io.github.manamiproject.modb.simkl.SimklConfig
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

internal class AnimeCountdownUrlAdderTest {

    @Test
    fun `correctly adds URI in sources`() {
        // given
        val testAnime = AnimeRaw(
            _title = "Test",
            _sources = hashSetOf(
                SimklConfig.buildAnimeLink("1535"),
            ),
        )

        val expectedAnime = AnimeRaw(
            _title = "Test",
            _sources = hashSetOf(
                AnimeCountdownConfig.buildAnimeLink("1535"),
                SimklConfig.buildAnimeLink("1535"),
            ),
        )

        // when
        val result = AnimeCountdownUrlAdder.addAnimeCountdown(listOf(testAnime))

        // then
        assertThat(result).containsExactly(expectedAnime)
    }

    @Test
    fun `correctly adds URI in relatedAnime`() {
        // given
        val testAnime = AnimeRaw(
            _title = "Test",
            _relatedAnime = hashSetOf(
                RelatedAnime(SimklConfig.buildAnimeLink("1535")),
            ),
        )

        val expectedAnime = AnimeRaw(
            _title = "Test",
            _relatedAnime = hashSetOf(
                RelatedAnime(AnimeCountdownConfig.buildAnimeLink("1535")),
                RelatedAnime(SimklConfig.buildAnimeLink("1535")),
            ),
        )

        // when
        val result = AnimeCountdownUrlAdder.addAnimeCountdown(listOf(testAnime))

        // then
        assertThat(result).containsExactly(expectedAnime)
    }

    @Test
    fun `keeps the relation when adding URI in relatedAnime`() {
        // given
        val testAnime = AnimeRaw(
            _title = "Test",
            _relatedAnime = hashSetOf(
                RelatedAnime(SimklConfig.buildAnimeLink("1535"), "Sequel"),
            ),
        )

        val expectedAnime = AnimeRaw(
            _title = "Test",
            _relatedAnime = hashSetOf(
                RelatedAnime(AnimeCountdownConfig.buildAnimeLink("1535"), "Sequel"),
                RelatedAnime(SimklConfig.buildAnimeLink("1535"), "Sequel"),
            ),
        )

        // when
        val result = AnimeCountdownUrlAdder.addAnimeCountdown(listOf(testAnime))

        // then
        assertThat(result).containsExactly(expectedAnime)
    }

    @Test
    fun `leaves sources property of anime without simkl source unaffected`() {
        // given
        val testAnime = AnimeRaw(
            _title = "Test",
            _sources = hashSetOf(
                MyanimelistConfig.buildAnimeLink("1535"),
                AnilistConfig.buildAnimeLink("1535"),
            ),
        )

        // when
        val result = AnimeCountdownUrlAdder.addAnimeCountdown(listOf(testAnime))

        // then
        assertThat(result).containsExactly(testAnime)
    }

    @Test
    fun `leaves relatedAnime property of anime without simkl source unaffected`() {
        // given
        val testAnime = AnimeRaw(
            _title = "Test",
            _relatedAnime = hashSetOf(
                RelatedAnime(MyanimelistConfig.buildAnimeLink("1535")),
                RelatedAnime(AnilistConfig.buildAnimeLink("1535")),
            ),
        )

        // when
        val result = AnimeCountdownUrlAdder.addAnimeCountdown(listOf(testAnime))

        // then
        assertThat(result).containsExactly(testAnime)
    }
}