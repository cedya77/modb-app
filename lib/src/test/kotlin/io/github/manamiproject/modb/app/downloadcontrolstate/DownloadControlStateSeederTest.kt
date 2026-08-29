package io.github.manamiproject.modb.app.downloadcontrolstate

import io.github.manamiproject.modb.core.date.WeekOfYear
import io.github.manamiproject.modb.core.date.compareTo
import io.github.manamiproject.modb.core.extensions.copyTo
import io.github.manamiproject.modb.test.tempDirectory
import io.github.manamiproject.modb.test.testResource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test

internal class DownloadControlStateSeederTest {

    @Nested
    inner class SeedTests {

        @Test
        fun `writes one entry per source and schedules them across a quarter`() {
            tempDirectory {
                // given
                val dataset = tempDir.resolve("dataset.jsonl")
                testResource("merging/lock/MergeLockSeederTest/dataset.jsonl").copyTo(dataset)
                val currentWeek = WeekOfYear(year = 2026, week = 35)

                // when
                val result = runBlocking {
                    DownloadControlStateSeeder().seed(dataset, tempDir, currentWeek)
                }

                // then
                assertThat(result).isNotEmpty
                // The fixture names 13 sources, one of which is animecountdown.com. Those URLs are
                // derived from simkl rather than crawled, so they must not get an entry of their own.
                assertThat(result.values.sum()).isEqualTo(12)
                assertThat(result).doesNotContainKey("animecountdown.com")

                val malEntries = tempDir.resolve("myanimelist.net").listDirectoryEntries()
                assertThat(malEntries).isNotEmpty
                assertThat(malEntries.map { it.fileName.toString() }).allMatch { it.endsWith(".dcs") }
            }
        }

        @Test
        fun `the seeded entries are readable by the accessor the pipeline uses`() {
            tempDirectory {
                // given
                val dataset = tempDir.resolve("dataset.jsonl")
                testResource("merging/lock/MergeLockSeederTest/dataset.jsonl").copyTo(dataset)
                val currentWeek = WeekOfYear(year = 2026, week = 35)
                runBlocking { DownloadControlStateSeeder().seed(dataset, tempDir, currentWeek) }

                val malId = tempDir.resolve("myanimelist.net")
                    .listDirectoryEntries()
                    .first()
                    .fileName.toString()
                    .removeSuffix(".dcs")

                // when
                val entry = runBlocking {
                    io.github.manamiproject.modb.core.json.Json.parseJson<DownloadControlStateEntry>(
                        tempDir.resolve("myanimelist.net").resolve("$malId.dcs").toFile().readText()
                    )!!
                }

                // then
                assertThat(entry.weeksWihoutChange).isZero()
                assertThat(entry.lastDownloaded).isEqualTo(currentWeek)
                assertThat(entry.nextDownload > currentWeek).isTrue()
                assertThat(entry.anime.sources).hasSize(1)
                assertThat(entry.anime.title).isNotBlank()
            }
        }
    }
}
