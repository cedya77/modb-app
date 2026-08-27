package io.github.manamiproject.modb.app.crawlers

import io.github.manamiproject.modb.app.TestConfigRegistry
import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.downloader.Downloader
import io.github.manamiproject.modb.test.exceptionExpected
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import kotlin.test.Test

internal class FaultTolerantDownloaderTest {

    @Nested
    inner class DownloadTests {

        @Test
        fun `skips an entry which cannot be downloaded`() {
            runBlocking {
                // given
                val downloader = FaultTolerantDownloader(
                    downloader = failing(times = 1, then = "content"),
                    hostname = "example.org",
                    configRegistry = limit(25),
                )

                // when
                val skipped = downloader.download("1") { }
                val next = downloader.download("2") { }

                // then
                assertThat(skipped).isEmpty()
                assertThat(next).isEqualTo("content")
            }
        }

        @Test
        fun `gives up once the provider has refused often enough in a row`() {
            // given
            val downloader = FaultTolerantDownloader(
                downloader = failing(times = 10, then = "content"),
                hostname = "example.org",
                configRegistry = limit(3),
            )

            // when
            val result = exceptionExpected<IllegalStateException> {
                repeat(3) { downloader.download(it.toString()) { } }
            }

            // then
            assertThat(result).hasMessage("nope")
        }

        @Test
        fun `a success resets the count so isolated failures never add up`() {
            runBlocking {
                // given
                val downloader = FaultTolerantDownloader(
                    downloader = alternating(),
                    hostname = "example.org",
                    configRegistry = limit(2),
                )

                // when
                val results = (1..6).map { downloader.download(it.toString()) { } }

                // then
                assertThat(results.filter { it.isNotEmpty() }).hasSize(3)
            }
        }
    }

    private fun limit(value: Int): ConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
        override fun int(key: String): Int = value
    }

    private fun failing(times: Int, then: String) = object: Downloader {
        private var calls = 0
        override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String {
            calls++
            if (calls <= times) throw IllegalStateException("nope")
            return then
        }
    }

    private fun alternating() = object: Downloader {
        private var calls = 0
        override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String {
            calls++
            if (calls % 2 == 1) throw IllegalStateException("nope")
            return "content"
        }
    }
}
