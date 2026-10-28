package io.github.manamiproject.modb.app.synonyms

import io.github.manamiproject.modb.core.anime.AnimeRaw
import io.github.manamiproject.modb.core.anime.Title
import io.github.manamiproject.modb.core.extensions.normalize
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import java.net.URI
import java.text.Normalizer

/**
 * A synonym is only removed if the entry proves that it is the plain name of the franchise and a related entry
 * answers to it as well. Both conditions are needed, because a name which is shared with a related entry can still
 * be the only name an entry has, and a name which looks unqualified can still belong to a standalone anime.
 * @since 1.0.0
 */
class DefaultAmbiguousSynonymRemover: AmbiguousSynonymRemover {

    override fun removeSynonymsWhichNameARelatedAnime(allEntries: List<AnimeRaw>): List<AnimeRaw> {
        log.info { "Removing synonyms which name a related anime." }

        val indexBySource = HashMap<URI, Int>()
        allEntries.forEachIndexed { index, anime ->
            anime.sources.forEach { indexBySource[it] = index }
        }

        val namesOf = allEntries.map { anime ->
            val names = HashSet<String>(anime.synonyms.size + 1)
            anime.synonyms.forEach { names.add(comparableForm(it)) }
            names.add(comparableForm(anime.title))
            names
        }

        var removed = 0

        allEntries.forEachIndexed { index, anime ->
            val relatedEntries = anime.relatedAnime.mapNotNull { indexBySource[it.uri] }
                .filterNot { it == index }
                .distinct()

            if (relatedEntries.isEmpty()) {
                return@forEachIndexed
            }

            val ownNames = namesOf[index]
            val ownTitle = comparableForm(anime.title)
            val numberOfSynonyms = anime.synonyms.size

            anime.removeSynonymIf { synonym ->
                val name = comparableForm(synonym)

                if (name == ownTitle || !hasSequelQualifiedVariant(ownNames, name)) {
                    return@removeSynonymIf false
                }

                relatedEntries.filter { name in namesOf[it] }
                    .any { !hasSequelQualifiedVariant(namesOf[it], name) }
            }

            removed += numberOfSynonyms - anime.synonyms.size
        }

        log.info { "Removed [$removed] synonyms which name a related anime." }

        return allEntries
    }

    /**
     * Whether the given set of names contains the same name followed by something which marks a position in a
     * sequence. A year is not such a marker. It tells two adaptations of the same material apart, so both of them
     * carry the plain name with equal right.
     */
    private fun hasSequelQualifiedVariant(names: Set<String>, name: String): Boolean = names.any { candidate ->
        when {
            candidate.length <= name.length -> false
            !candidate.startsWith(name) -> false
            candidate[name.length].isLetterOrDigit() -> false
            else -> {
                val qualifier = candidate.substring(name.length).trim { it in SEPARATORS }
                qualifier.isNotEmpty() && !YEAR.matches(qualifier) && SEQUENCE_MARKER.containsMatchIn(qualifier)
            }
        }
    }

    private fun comparableForm(title: Title): String = Normalizer.normalize(title.normalize(), Normalizer.Form.NFC)
        .replace(CONSECUTIVE_WHITESPACE, " ")
        .trim()
        .lowercase()

    companion object {
        private val log by LoggerDelegate()

        private const val SEPARATORS = " :：-–—,、。.·()（）[]"
        private val CONSECUTIVE_WHITESPACE = Regex("""(?U)\s+""")
        private val YEAR = Regex("""(?U)\(?(19|20)\d{2}\)?""")
        private val SEQUENCE_MARKER = Regex("""(?U)(\d|\bii+\b|\biv\b|\bseason\b|\bpart\b|\bfinal\b|\bmovie\b|期|季|部)""")

        /**
         * Singleton of [DefaultAmbiguousSynonymRemover]
         * @since 1.0.0
         */
        val instance: DefaultAmbiguousSynonymRemover by lazy { DefaultAmbiguousSynonymRemover() }
    }
}
