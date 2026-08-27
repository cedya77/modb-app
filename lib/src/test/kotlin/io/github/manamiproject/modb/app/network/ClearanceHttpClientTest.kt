package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.app.TestConfigRegistry
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.HttpResponse
import io.github.manamiproject.modb.core.httpclient.RequestBody
import io.github.manamiproject.modb.core.httpclient.RetryCase
import io.github.manamiproject.modb.test.shouldNotBeInvoked
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import java.net.URI
import java.net.URL
import kotlin.test.Test

internal class ClearanceHttpClientTest {

    private val solved = """{"status":"ok","solution":{"status":200,"userAgent":"Mozilla/5.0 (X11)","cookies":[{"name":"cf_clearance","value":"abc"},{"name":"adbuin","value":"1"}]}}"""
    private val refused = """{"status":"error","message":"Error solving the challenge."}"""

    @Nested
    inner class GetTests {

        @Test
        fun `obtains a clearance before the first request and presents it`() {
            runBlocking {
                // given
                val sent = mutableListOf<Map<String, Collection<String>>>()
                val client = ClearanceHttpClient(
                    clearance = clearanceWith(solved),
                    httpClient = recording(sent, 200),
                )

                // when
                val result = client.get(URI("https://anidb.net/anime/23").toURL())

                // then
                assertThat(result.code).isEqualTo(200)
                assertThat(sent).hasSize(1)
                assertThat(sent.first()["Cookie"]?.first()).isEqualTo("cf_clearance=abc; adbuin=1")
                assertThat(sent.first()["User-Agent"]?.first()).isEqualTo("Mozilla/5.0 (X11)")
            }
        }

        @Test
        fun `renews the clearance once when the site refuses it`() {
            runBlocking {
                // given
                val sent = mutableListOf<Map<String, Collection<String>>>()
                val codes = mutableListOf(403, 200)
                val client = ClearanceHttpClient(
                    clearance = clearanceWith(solved),
                    httpClient = object: HttpClient {
                        override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse {
                            sent.add(headers)
                            return HttpResponse(codes.removeFirst(), "body")
                        }
                        override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
                        override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
                    },
                )

                // when
                val result = client.get(URI("https://anidb.net/anime/23").toURL())

                // then
                assertThat(result.code).isEqualTo(200)
                assertThat(sent).hasSize(2)
            }
        }

        @Test
        fun `returns the refusal when no clearance can be obtained`() {
            runBlocking {
                // given
                val sent = mutableListOf<Map<String, Collection<String>>>()
                val client = ClearanceHttpClient(
                    clearance = clearanceWith(refused),
                    httpClient = recording(sent, 403),
                )

                // when
                val result = client.get(URI("https://anidb.net/anime/23").toURL())

                // then
                assertThat(result.code).isEqualTo(403)
                assertThat(sent.first()).doesNotContainKey("Cookie")
            }
        }
    }

    private fun clearanceWith(solverResponse: String) = CloudflareClearance(
        configRegistry = object: ConfigRegistry by TestConfigRegistry {
            override fun string(key: String): String = "http://solver.invalid:8191/v1"
        },
        httpClient = object: HttpClient {
            override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
            override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse {
                return HttpResponse(200, solverResponse)
            }
            override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
        },
    )

    private fun recording(sent: MutableList<Map<String, Collection<String>>>, code: Int) = object: HttpClient {
        override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse {
            sent.add(headers)
            return HttpResponse(code, "body")
        }
        override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
        override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
    }
}
