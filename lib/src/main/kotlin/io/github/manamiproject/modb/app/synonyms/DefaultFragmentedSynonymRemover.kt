package io.github.manamiproject.modb.app.synonyms

import io.github.manamiproject.modb.core.anime.AnimeRaw
import io.github.manamiproject.modb.core.anime.Title
import io.github.manamiproject.modb.core.extensions.normalize
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import java.text.Normalizer

/**
 * A synonym is only removed if it equals a piece which follows a comma in another name of the same entry and if it
 * doesn't look like a name on its own. Both conditions are needed, because the piece after a comma can be a complete
 * name as well, like the second half of `Natsu e no Tunnel, Sayonara no Deguchi`. The piece before the first comma is
 * never considered, because it usually is the main part of the name, like `Gintama` in `Gintama, the Movie`.
 * @since 1.0.0
 */
class DefaultFragmentedSynonymRemover: FragmentedSynonymRemover {

    override fun removeSynonymsWhichAreAFragmentOfAnotherName(allEntries: List<AnimeRaw>): List<AnimeRaw> {
        log.info { "Removing synonyms which are a fragment of another name." }

        var removed = 0

        allEntries.forEach { anime ->
            val fragments = (anime.synonyms + anime.title)
                .filter { it.contains(COMMA) }
                .flatMap { it.split(COMMA).drop(1) }
                .map { comparableForm(it) }
                .filter { it.isNotEmpty() }
                .toHashSet()

            if (fragments.isEmpty()) {
                return@forEach
            }

            val numberOfSynonyms = anime.synonyms.size

            anime.removeSynonymIf { synonym ->
                comparableForm(synonym) in fragments && looksLikeAFragment(synonym.trim())
            }

            removed += numberOfSynonyms - anime.synonyms.size
        }

        log.info { "Removed [$removed] synonyms which are a fragment of another name." }

        return allEntries
    }

    /**
     * A piece which starts in lower case continues a sentence, like `the Little Girl` or `and...`. A piece with hardly
     * any latin letters or digits is either a number which has been cut at its thousands separator, like `000`, or a
     * clause of a japanese name which used a latin comma, like `そしてフロンタルへ~`. A piece without any letter is a
     * leftover such as `2`.
     */
    private fun looksLikeAFragment(synonym: Title): Boolean = when {
        synonym.first().isLowerCase() -> true
        synonym.count { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } <= MAX_LATIN_CHARACTERS_OF_A_FRAGMENT -> true
        else -> synonym.none { it.isLetter() }
    }

    /**
     * Compatibility decomposition lets an ellipsis match three dots, so `and… 2` and `and... 2` are the same piece.
     */
    private fun comparableForm(title: Title): String = Normalizer.normalize(title.normalize(), Normalizer.Form.NFKC)
        .replace(CONSECUTIVE_WHITESPACE, " ")
        .trim()
        .lowercase()

    companion object {
        private val log by LoggerDelegate()

        private const val COMMA = ','
        private const val MAX_LATIN_CHARACTERS_OF_A_FRAGMENT = 3
        private val CONSECUTIVE_WHITESPACE = Regex("""(?U)\s+""")

        /**
         * Singleton of [DefaultFragmentedSynonymRemover]
         * @since 1.0.0
         */
        val instance: DefaultFragmentedSynonymRemover by lazy { DefaultFragmentedSynonymRemover() }
    }
}
