package io.github.manamiproject.modb.app.crawlers.anidb

import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.HttpResponse
import io.github.manamiproject.modb.core.httpclient.RequestBody
import io.github.manamiproject.modb.core.httpclient.RetryCase
import io.github.manamiproject.modb.test.exceptionExpected
import io.github.manamiproject.modb.test.shouldNotBeInvoked
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import java.net.URL
import kotlin.test.Test

internal class AnidbTitlesDumpHighestIdDetectorTest {

    @Nested
    inner class DetectHighestIdTests {

        @Test
        fun `reads the highest id from the gzipped dump`() {
            runBlocking {
                // given
                val dump = this::class.java.classLoader.getResourceAsStream("crawlers/anidb/anime-titles.dat.gz")!!.readBytes()
                val detector = AnidbTitlesDumpHighestIdDetector(httpClient = clientReturning(dump))

                // when
                val result = detector.detectHighestId()

                // then
                assertThat(result).isEqualTo(20320)
            }
        }

        @Test
        fun `ignores comments and reads every id`() {
            runBlocking {
                // given
                val dump = this::class.java.classLoader.getResourceAsStream("crawlers/anidb/anime-titles.dat.gz")!!.readBytes()
                val detector = AnidbTitlesDumpHighestIdDetector(httpClient = clientReturning(dump))

                // when
                val result = detector.allIds()

                // then
                assertThat(result).isNotEmpty
                assertThat(result).contains(20320)
                assertThat(result).allMatch { it > 0 }
            }
        }

        @Test
        fun `throws if the dump contains no id`() {
            // given
            val detector = AnidbTitlesDumpHighestIdDetector(
                httpClient = clientReturning("# created: whenever\n".toByteArray()),
            )

            // when
            val result = exceptionExpected<IllegalStateException> {
                detector.detectHighestId()
            }

            // then
            assertThat(result).hasMessage("Title dump did not contain any anime id.")
        }
    }

    private fun clientReturning(body: ByteArray): HttpClient = object: HttpClient {
        override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse {
            return HttpResponse(200, body)
        }
        override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
        override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
    }
}
