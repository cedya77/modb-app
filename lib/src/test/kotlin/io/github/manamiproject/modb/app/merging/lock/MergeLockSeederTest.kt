package io.github.manamiproject.modb.app.merging.lock

import io.github.manamiproject.modb.app.TestAppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.extensions.Directory
import io.github.manamiproject.modb.core.extensions.copyTo
import io.github.manamiproject.modb.test.exceptionExpected
import io.github.manamiproject.modb.test.tempDirectory
import io.github.manamiproject.modb.test.testResource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import java.net.URI
import kotlin.test.Test

internal class MergeLockSeederTest {

    @Nested
    inner class SeedTests {

        @Test
        fun `writes a merge lock per entry which has more than one source`() {
            tempDirectory {
                // given
                val dataset = tempDir.resolve("dataset.jsonl")
                testResource("merging/lock/MergeLockSeederTest/dataset.jsonl").copyTo(dataset)

                // when
                val result = runBlocking { MergeLockSeeder().seed(dataset, tempDir) }

                // then
                assertThat(result).isEqualTo(2)
                assertThat(tempDir.resolve("merge.lock")).exists()
            }
        }

        @Test
        fun `the seeded file is readable by the accessor the pipeline actually uses`() {
            tempDirectory {
                // given
                val dataset = tempDir.resolve("dataset.jsonl")
                testResource("merging/lock/MergeLockSeederTest/dataset.jsonl").copyTo(dataset)
                runBlocking { MergeLockSeeder().seed(dataset, tempDir) }

                val testAppConfig = object: Config by TestAppConfig {
                    override fun downloadControlStateDirectory(): Directory = tempDir
                }
                val accessor = DefaultMergeLockAccessor(appConfig = testAppConfig)

                // when
                val mergeLock = runBlocking { accessor.getMergeLock(URI("https://anilist.co/anime/142051")) }
                val skipped = runBlocking { accessor.isPartOfMergeLock(URI("https://anisearch.com/anime/18676")) }
                val allSources = runBlocking { accessor.allSourcesInAllMergeLockEntries() }

                // then
                assertThat(mergeLock.map { it.toString() }).containsExactlyInAnyOrderElementsOf(
                    listOf("https://anilist.co/anime/142051", "https://anime-planet.com/anime/raise-a-suilen-nvade-show", "https://kitsu.app/anime/47450", "https://myanimelist.net/anime/51478")
                )
                assertThat(skipped).isFalse()
                assertThat(allSources).hasSize(12)
            }
        }

        @Test
        fun `throws if a source is part of more than one entry`() {
            tempDirectory {
                // given
                val dataset = tempDir.resolve("duplicate-source.jsonl")
                testResource("merging/lock/MergeLockSeederTest/duplicate-source.jsonl").copyTo(dataset)

                // when
                val result = exceptionExpected<IllegalStateException> {
                    MergeLockSeeder().seed(dataset, tempDir)
                }

                // then
                assertThat(result).hasMessageContaining("https://anilist.co/anime/142051")
            }
        }
    }
}
