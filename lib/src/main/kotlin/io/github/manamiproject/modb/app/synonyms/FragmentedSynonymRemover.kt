package io.github.manamiproject.modb.app.synonyms

import io.github.manamiproject.modb.core.anime.AnimeRaw

/**
 * Removes synonyms which are only a piece of another name of the same entry.
 * @since 1.0.0
 */
interface FragmentedSynonymRemover {

    /**
     * Some metadata providers list all synonyms of an entry as a single text in which the synonyms are separated by
     * commas. A synonym which contains a comma itself is torn apart when that text is split. Its pieces end up as
     * synonyms of their own, for example `and...` from `I Admire Magical Girls, and...`, while another metadata
     * provider contributes the intact name.
     * @since 1.0.0
     * @param allEntries All entries of the dataset after merging.
     * @return The same list. Entries are modified in place.
     */
    fun removeSynonymsWhichAreAFragmentOfAnotherName(allEntries: List<AnimeRaw>): List<AnimeRaw>
}
