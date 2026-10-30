package io.github.manamiproject.modb.app.crawlers.anidb

import io.github.manamiproject.modb.app.TestAppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.app.crawlers.anidb.DefaultAnidbRestrictedEntries.Companion.FILE_NAME
import io.github.manamiproject.modb.core.extensions.Directory
import io.github.manamiproject.modb.test.tempDirectory
import org.assertj.core.api.Assertions.assertThat
import kotlin.io.path.readLines
import kotlin.test.Test

internal class DefaultAnidbRestrictedEntriesTest {

    private fun testAppConfig(dir: Directory) = object: Config by TestAppConfig {
        override fun downloadControlStateDirectory(): Directory = dir
    }

    @Test
    fun `keeps added entries in the download control state directory`() {
        tempDirectory {
            // given
            val restrictedEntries = DefaultAnidbRestrictedEntries(appConfig = testAppConfig(tempDir))

            // when
            restrictedEntries.add(3531)
            restrictedEntries.add(1202)

            // then
            assertThat(tempDir.resolve(FILE_NAME).readLines().filterNot { it.startsWith("#") })
                .containsExactly("1202", "3531")
            assertThat(DefaultAnidbRestrictedEntries(appConfig = testAppConfig(tempDir)).all())
                .containsExactlyInAnyOrder(1202, 3531)
        }
    }

    @Test
    fun `removes an entry`() {
        tempDirectory {
            // given
            val restrictedEntries = DefaultAnidbRestrictedEntries(appConfig = testAppConfig(tempDir))
            restrictedEntries.add(3531)
            restrictedEntries.add(1202)

            // when
            restrictedEntries.remove(3531)

            // then
            assertThat(restrictedEntries.all()).containsExactly(1202)
        }
    }

    @Test
    fun `removes entries which the title dump no longer names`() {
        tempDirectory {
            // given
            val restrictedEntries = DefaultAnidbRestrictedEntries(appConfig = testAppConfig(tempDir))
            restrictedEntries.add(3531)
            restrictedEntries.add(1202)

            // when
            restrictedEntries.removeAllNotIn(setOf(3531, 1))

            // then
            assertThat(restrictedEntries.all()).containsExactly(3531)
        }
    }
}
