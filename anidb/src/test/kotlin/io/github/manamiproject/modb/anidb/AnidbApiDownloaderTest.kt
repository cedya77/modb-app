package io.github.manamiproject.modb.anidb

import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.HttpResponse
import io.github.manamiproject.modb.core.httpclient.RequestBody
import io.github.manamiproject.modb.core.httpclient.RetryCase
import io.github.manamiproject.modb.test.exceptionExpected
import io.github.manamiproject.modb.test.shouldNotBeInvoked
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import java.net.URL
import kotlin.test.Test

internal class AnidbApiDownloaderTest {

    @Nested
    inner class BannedTests {

        @Test
        fun `retries if the API responds with banned and returns the body once the ban is over`() {
            runTest {
                // given
                var invocations = 0
                val testHttpClient = respondingWith {
                    invocations++
                    when (invocations) {
                        1, 2 -> """<error code="500">banned</error>"""
                        else -> """<anime id="1"></anime>"""
                    }
                }

                val downloader = AnidbApiDownloader(
                    metaDataProviderConfig = testAnidbConfig,
                    configRegistry = testConfigRegistry,
                    httpClient = testHttpClient,
                )

                // when
                val result = downloader.download("1") { shouldNotBeInvoked() }

                // then
                assertThat(result).isEqualTo("""<anime id="1"></anime>""")
                assertThat(invocations).isEqualTo(3)
            }
        }

        @Test
        fun `throws AnidbBannedException if the ban is still active after all retries`() {
            // given
            var invocations = 0
            val testHttpClient = respondingWith {
                invocations++
                """<error code="500">banned</error>"""
            }

            val downloader = AnidbApiDownloader(
                metaDataProviderConfig = testAnidbConfig,
                configRegistry = testConfigRegistry,
                httpClient = testHttpClient,
            )

            // when
            val result = exceptionExpected<AnidbBannedException> {
                downloader.download("1") { shouldNotBeInvoked() }
            }

            // then
            assertThat(result).hasMessage("API responded with error [code=500], [message=banned] for [anidbId=1].")
            assertThat(invocations).isEqualTo(6)
        }

        @Test
        fun `number of retries while banned can be set via banRetries`() {
            // given
            var invocations = 0
            val testHttpClient = respondingWith {
                invocations++
                """<error code="500">banned</error>"""
            }

            val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                override fun string(key: String): String = "test"
                override fun int(key: String): Int? = if (key == "modb.anidb.banRetries") 2 else null
            }

            val downloader = AnidbApiDownloader(
                metaDataProviderConfig = testAnidbConfig,
                configRegistry = testConfigRegistry,
                httpClient = testHttpClient,
            )

            // when
            exceptionExpected<AnidbBannedException> {
                downloader.download("1") { shouldNotBeInvoked() }
            }

            // then
            assertThat(invocations).isEqualTo(3)
        }

        @Test
        fun `other errors are not retried`() {
            // given
            var invocations = 0
            val testHttpClient = respondingWith {
                invocations++
                """<error code="302">client version missing or invalid</error>"""
            }

            val downloader = AnidbApiDownloader(
                metaDataProviderConfig = testAnidbConfig,
                configRegistry = testConfigRegistry,
                httpClient = testHttpClient,
            )

            // when
            val result = exceptionExpected<IllegalStateException> {
                downloader.download("1") { shouldNotBeInvoked() }
            }

            // then
            assertThat(result).hasMessage("API responded with error [code=302], [message=client version missing or invalid] for [anidbId=1].")
            assertThat(invocations).isEqualTo(1)
        }
    }

    private val testAnidbConfig = object: MetaDataProviderConfig by AnidbConfig {
        override fun isTestContext(): Boolean = true
    }

    private val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
        override fun string(key: String): String = "test"
        override fun int(key: String): Int? = null
    }

    private fun respondingWith(body: () -> String) = object: HttpClient {
        override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
        override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse = HttpResponse(200, body())
        override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = shouldNotBeInvoked()
    }
}
