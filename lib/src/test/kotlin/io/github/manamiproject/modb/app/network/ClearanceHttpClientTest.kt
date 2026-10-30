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
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import kotlin.test.Test

internal class ClearanceHttpClientTest {

    private val solved = """{"status":"ok","solution":{"status":200,"userAgent":"Mozilla/5.0 (X11)","cookies":[{"name":"cf_clearance","value":"abc"},{"name":"adbuin","value":"1"}]}}"""
    private val refused = """{"status":"error","message":"Error solving the challenge."}"""

    @Nested
    inner class GetTests {

        @Test
        fun `sends the request without a render when the site does not challenge`() {
            runBlocking {
                // given
                val sent = mutableListOf<Map<String, Collection<String>>>()
                val client = ClearanceHttpClient(
                    clearance = clearanceWith(null),
                    httpClient = recording(sent, 200),
                )

                // when
                val result = client.get(URI("https://www.anime-planet.com/anime/death-note").toURL())

                // then
                assertThat(result.code).isEqualTo(200)
                assertThat(sent).hasSize(1)
                assertThat(sent.first()).doesNotContainKey("Cookie")
            }
        }

        @Test
        fun `obtains a clearance once challenged and presents it`() {
            runBlocking {
                // given
                val sent = mutableListOf<Map<String, Collection<String>>>()
                val client = ClearanceHttpClient(
                    clearance = clearanceWith(solved),
                    httpClient = answering(sent, 403, 200),
                )

                // when
                val result = client.get(URI("https://anidb.net/anime/23").toURL())

                // then
                assertThat(result.code).isEqualTo(200)
                assertThat(sent).hasSize(2)
                assertThat(sent.last()["Cookie"]?.first()).isEqualTo("cf_clearance=abc; adbuin=1")
                assertThat(sent.last()["User-Agent"]?.first()).isEqualTo("Mozilla/5.0 (X11)")
            }
        }

        @Test
        fun `keeps presenting a clearance without rendering again`() {
            runBlocking {
                // given
                val sent = mutableListOf<Map<String, Collection<String>>>()
                var renders = 0
                val client = ClearanceHttpClient(
                    clearance = clearanceWith(solved) { renders++ },
                    httpClient = answering(sent, 403, 200, 200, 200),
                )

                // when
                repeat(3) { client.get(URI("https://anidb.net/anime/23").toURL()) }

                // then
                assertThat(renders).isOne()
                assertThat(sent.drop(1)).allSatisfy { assertThat(it["Cookie"]?.first()).isEqualTo("cf_clearance=abc; adbuin=1") }
            }
        }

        @Test
        fun `presents the clearance a route earned when rotating back to it`() {
            runBlocking {
                // given
                val sent = mutableListOf<Map<String, Collection<String>>>()
                var renders = 0
                var route = "http://127.0.0.1:1"
                val client = ClearanceHttpClient(
                    clearance = clearanceWith(solved) { renders++ },
                    httpClient = answering(sent, 403, 200, 403, 200, 200),
                    currentProxy = { route },
                )

                // when
                client.get(URI("https://anidb.net/anime/1").toURL())
                route = "http://127.0.0.1:2"
                client.get(URI("https://anidb.net/anime/2").toURL())
                route = "http://127.0.0.1:1"
                client.get(URI("https://anidb.net/anime/3").toURL())

                // then
                assertThat(renders).isEqualTo(2)
                assertThat(sent.last()["Cookie"]?.first()).isEqualTo("cf_clearance=abc; adbuin=1")
            }
        }

        @Test
        fun `changes connection when a fresh clearance is refused as well`() {
            runBlocking {
                // given
                val sent = mutableListOf<Map<String, Collection<String>>>()
                var connectionChanges = 0
                val client = ClearanceHttpClient(
                    clearance = clearanceWith(solved),
                    httpClient = answering(sent, 403, 403, 200),
                    changeConnection = { connectionChanges++ },
                )

                // when
                val result = client.get(URI("https://anidb.net/anime/23").toURL())

                // then
                assertThat(result.code).isEqualTo(200)
                assertThat(connectionChanges).isOne()
            }
        }

        @Test
        fun `closes every refusal it doesn't return`() {
            runBlocking {
                // given
                val responses = mutableListOf<HttpResponse>()
                val codes = mutableListOf(403, 403, 200)
                val client = ClearanceHttpClient(
                    clearance = clearanceWith(solved),
                    httpClient = object: HttpClient {
                        override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse {
                            return HttpResponse(codes.removeFirst(), "body").also { responses.add(it) }
                        }
                        override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
                        override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
                    },
                )

                // when
                val result = client.get(URI("https://anidb.net/anime/23").toURL())

                // then
                assertThat(result.code).isEqualTo(200)
                assertThat(responses).hasSize(3)
                assertThat(responses[0].isBodyInputStreamExhausted()).isTrue()
                assertThat(responses[1].isBodyInputStreamExhausted()).isTrue()
                assertThat(result.isBodyInputStreamExhausted()).isFalse()
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

        @Test
        fun `changes connection and repeats the request when the route cannot reach the site`() {
            runBlocking {
                // given
                var attempts = 0
                var connectionChanges = 0
                val client = ClearanceHttpClient(
                    clearance = clearanceWith(solved),
                    httpClient = failingFor(1) { attempts++ },
                    changeConnection = { connectionChanges++ },
                )

                // when
                val result = client.get(URI("https://www.anime-planet.com/anime/death-note").toURL())

                // then
                assertThat(result.code).isEqualTo(200)
                assertThat(attempts).isEqualTo(2)
                assertThat(connectionChanges).isEqualTo(1)
            }
        }

        @Test
        fun `lets a failure which is not about the route travel without changing connection`() {
            runBlocking {
                // given
                var connectionChanges = 0
                val client = ClearanceHttpClient(
                    clearance = clearanceWith(solved),
                    httpClient = object: HttpClient {
                        override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse = throw SocketTimeoutException("timeout")
                        override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
                        override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
                    },
                    changeConnection = { connectionChanges++ },
                )

                // when
                val result = assertThrows<SocketTimeoutException> {
                    client.get(URI("https://www.anime-planet.com/anime/death-note").toURL())
                }

                // then
                assertThat(result).hasMessage("timeout")
                assertThat(connectionChanges).isZero()
            }
        }

        @Test
        fun `gives the request up once the connection has been changed the maximum number of times`() {
            runBlocking {
                // given
                var attempts = 0
                var connectionChanges = 0
                val client = ClearanceHttpClient(
                    clearance = clearanceWith(solved),
                    httpClient = failingFor(Int.MAX_VALUE) { attempts++ },
                    changeConnection = { connectionChanges++ },
                    maxConnectionChanges = 2,
                )

                // when
                val result = assertThrows<IOException> {
                    client.get(URI("https://www.anime-planet.com/anime/death-note").toURL())
                }

                // then
                assertThat(result).hasMessage("Unexpected response code for CONNECT: 503")
                assertThat(attempts).isEqualTo(3)
                assertThat(connectionChanges).isEqualTo(2)
            }
        }
    }

    private fun failingFor(times: Int, onAttempt: () -> Unit) = object: HttpClient {
        private var failures = 0
        override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse {
            onAttempt()
            if (failures++ < times) {
                throw IOException("Unexpected response code for CONNECT: 503")
            }
            return HttpResponse(200, "body")
        }
        override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
        override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
    }

    private fun clearanceWith(solverResponse: String?, onRender: () -> Unit = {}) = CloudflareClearance(
        configRegistry = object: ConfigRegistry by TestConfigRegistry {
            override fun string(key: String): String? = when (key) {
                "modb.app.challenge.solverUrl" -> "http://solver.invalid:8191/v1"
                else -> null
            }
            override fun int(key: String): Int? = null
        },
        httpClient = object: HttpClient {
            override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
            override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse {
                onRender()
                return HttpResponse(200, solverResponse ?: shouldNotBeInvoked())
            }
            override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
        },
    )

    private fun answering(sent: MutableList<Map<String, Collection<String>>>, vararg codes: Int) = object: HttpClient {
        private val remaining = codes.toMutableList()
        override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse {
            sent.add(headers)
            return HttpResponse(remaining.removeFirst(), "body")
        }
        override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
        override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
    }

    private fun recording(sent: MutableList<Map<String, Collection<String>>>, code: Int) = object: HttpClient {
        override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse {
            sent.add(headers)
            return HttpResponse(code, "body")
        }
        override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
        override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
    }
}
