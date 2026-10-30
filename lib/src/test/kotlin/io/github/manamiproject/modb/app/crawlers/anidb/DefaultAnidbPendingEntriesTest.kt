package io.github.manamiproject.modb.app.crawlers.anidb

import io.github.manamiproject.modb.app.TestAppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.app.crawlers.anidb.DefaultAnidbPendingEntries.Companion.FILE_NAME
import io.github.manamiproject.modb.core.extensions.Directory
import io.github.manamiproject.modb.test.tempDirectory
import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset.UTC
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test

internal class DefaultAnidbPendingEntriesTest {

    private fun testAppConfig(dir: Directory, day: String = "2026-10-29") = object: Config by TestAppConfig {
        override fun downloadControlStateDirectory(): Directory = dir
        override fun clock(): Clock = Clock.fixed(Instant.parse("${day}T12:00:00Z"), UTC)
    }

    @Test
    fun `keeps added entries in the download control state directory`() {
        tempDirectory {
            // given
            val pendingEntries = DefaultAnidbPendingEntries(appConfig = testAppConfig(tempDir))

            // when
            pendingEntries.add(20227)
            pendingEntries.add(19000)

            // then
            assertThat(tempDir.resolve(FILE_NAME).readLines().filterNot { it.startsWith("#") })
                .containsExactly("19000 2026-10-29", "20227 2026-10-29")
            assertThat(DefaultAnidbPendingEntries(appConfig = testAppConfig(tempDir)).all())
                .containsExactlyInAnyOrder(19000, 20227)
        }
    }

    @Test
    fun `removes an entry`() {
        tempDirectory {
            // given
            val pendingEntries = DefaultAnidbPendingEntries(appConfig = testAppConfig(tempDir))
            pendingEntries.add(20227)
            pendingEntries.add(19000)

            // when
            pendingEntries.remove(20227)

            // then
            assertThat(pendingEntries.all()).containsExactly(19000)
        }
    }

    @Test
    fun `removes entries which the title dump no longer names`() {
        tempDirectory {
            // given
            val pendingEntries = DefaultAnidbPendingEntries(appConfig = testAppConfig(tempDir))
            pendingEntries.add(20227)
            pendingEntries.add(19000)

            // when
            pendingEntries.removeAllNotIn(setOf(20227, 1))

            // then
            assertThat(pendingEntries.all()).containsExactly(20227)
        }
    }

    @Test
    fun `selects the entries which have been checked longest ago and marks them as checked today`() {
        tempDirectory {
            // given
            tempDir.resolve(FILE_NAME).writeText("""
                # comment
                100 2026-10-20
                200 2026-10-01
                300 2026-10-25
            """.trimIndent())
            val pendingEntries = DefaultAnidbPendingEntries(appConfig = testAppConfig(tempDir))

            // when
            val result = pendingEntries.selectForRecheck(2)

            // then
            assertThat(result).containsExactlyInAnyOrder(100, 200)
            assertThat(tempDir.resolve(FILE_NAME).readLines().filterNot { it.startsWith("#") })
                .containsExactly("100 2026-10-29", "200 2026-10-29", "300 2026-10-25")
        }
    }

    @Test
    fun `selects nothing if no recheck is configured`() {
        tempDirectory {
            // given
            val pendingEntries = DefaultAnidbPendingEntries(appConfig = testAppConfig(tempDir))
            pendingEntries.add(20227)

            // when
            val result = pendingEntries.selectForRecheck(0)

            // then
            assertThat(result).isEmpty()
        }
    }
}
