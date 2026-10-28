package io.github.manamiproject.modb.app.dataset

import io.github.manamiproject.modb.animecountdown.AnimeCountdownConfig
import io.github.manamiproject.modb.core.anime.AnimeRaw
import io.github.manamiproject.modb.core.anime.RelatedAnime
import io.github.manamiproject.modb.simkl.SimklConfig

object AnimeCountdownUrlAdder {

    fun addAnimeCountdown(list: List<AnimeRaw>): List<AnimeRaw> {
        return list.map { anime ->
            val sources = anime.sources.filter { source -> source.host == SimklConfig.hostname() }
                .map { SimklConfig.extractAnimeId(it) }
                .map { AnimeCountdownConfig.buildAnimeLink(it) }
            val relatedAnime = anime.relatedAnime.filter { related -> related.uri.host == SimklConfig.hostname() }
                .map { related -> RelatedAnime(AnimeCountdownConfig.buildAnimeLink(SimklConfig.extractAnimeId(related.uri)), related.relation) }

            anime.addSources(sources)
            anime.addRelatedAnime(relatedAnime)
        }
    }
}