package io.github.manamiproject.modb.app.merging

import io.github.manamiproject.modb.test.loadTestResource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import java.net.URI
import kotlin.test.Test

internal class AnidbCrossReferencesTest {

    @Nested
    inner class ExtractTests {

        @Test
        fun `returns the links which AniDB names unambiguously`() {
            runBlocking {
                // given
                val rawContent = loadTestResource<String>("crossreferences/anidb-19983.xml")

                // when
                val result = AnidbCrossReferences().extract(rawContent)

                // then
                assertThat(result).containsExactlyInAnyOrder(
                    URI("https://myanimelist.net/anime/63832"),
                    URI("https://animenewsnetwork.com/encyclopedia/anime.php?id=38846"),
                )
            }
        }

        @Test
        fun `drops a provider which AniDB links to more than once`() {
            runBlocking {
                // given
                val rawContent = loadTestResource<String>("crossreferences/anidb-23.xml")

                // when
                val result = AnidbCrossReferences().extract(rawContent)

                // then
                assertThat(result).containsExactlyInAnyOrder(
                    URI("https://animenewsnetwork.com/encyclopedia/anime.php?id=13"),
                    URI("https://anime-planet.com/anime/cowboy-bebop"),
                )
                assertThat(result.map { it.toString() }).noneMatch { it.contains("myanimelist") }
            }
        }
    }
}
