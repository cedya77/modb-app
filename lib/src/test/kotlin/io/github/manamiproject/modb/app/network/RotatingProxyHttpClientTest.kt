package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.app.TestAppConfig
import io.github.manamiproject.modb.app.TestConfigRegistry
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.HttpResponse
import io.github.manamiproject.modb.core.httpclient.RequestBody
import io.github.manamiproject.modb.core.httpclient.RetryCase
import io.github.manamiproject.modb.test.exceptionExpected
import io.github.manamiproject.modb.test.shouldNotBeInvoked
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import java.net.ConnectException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset.UTC
import kotlin.test.Test

internal class RotatingProxyHttpClientTest {

    @Nested
    inner class GetTests {

        @Test
        fun `changes route and retries when a proxy refuses the connection`() {
            runBlocking {
                // given
                val refused = mutableListOf<Proxy>()
                val client = RotatingProxyHttpClient(
                    networkController = controllerOf("127.0.0.1:1111", "127.0.0.1:2222"),
                    configRegistry = defaultConfig(),
                    httpClientFactory = { proxy ->
                        when (proxy.address().toString()) {
                            "/127.0.0.1:1111" -> throwingClient(proxy, refused, ConnectException("Connection refused"))
                            else -> respondingClient("second")
                        }
                    },
                )

                // when
                val result = client.get(URI("https://example.org/anime/23").toURL())

                // then
                assertThat(result.bodyAsString()).isEqualTo("second")
                assertThat(refused).hasSize(1)
            }
        }

        @Test
        fun `gives up once every route has refused`() {
            runBlocking {
                // given
                val client = RotatingProxyHttpClient(
                    networkController = controllerOf("127.0.0.1:1111", "127.0.0.1:2222"),
                    configRegistry = maxConnectionChanges(2),
                    httpClientFactory = { proxy -> throwingClient(proxy, mutableListOf(), ConnectException("Connection refused")) },
                )

                // when
                val result = exceptionExpected<ConnectException> {
                    client.get(URI("https://example.org/anime/23").toURL())
                }

                // then
                assertThat(result).hasMessage("Connection refused")
            }
        }

        @Test
        fun `hands a failure which is not a refused connection to the caller`() {
            runBlocking {
                // given
                val client = RotatingProxyHttpClient(
                    networkController = controllerOf("127.0.0.1:1111", "127.0.0.1:2222"),
                    configRegistry = defaultConfig(),
                    httpClientFactory = { proxy -> throwingClient(proxy, mutableListOf(), SocketTimeoutException("Read timed out")) },
                )

                // when
                val result = exceptionExpected<SocketTimeoutException> {
                    client.get(URI("https://example.org/anime/23").toURL())
                }

                // then
                assertThat(result).hasMessage("Read timed out")
            }
        }

        @Test
        fun `treats a refusal wrapped in another failure as a refused route`() {
            runBlocking {
                // given
                val refused = mutableListOf<Proxy>()
                val client = RotatingProxyHttpClient(
                    networkController = controllerOf("127.0.0.1:1111", "127.0.0.1:2222"),
                    configRegistry = defaultConfig(),
                    httpClientFactory = { proxy ->
                        when (proxy.address().toString()) {
                            "/127.0.0.1:1111" -> throwingClient(
                                proxy,
                                refused,
                                ConnectException("Failed to connect").apply { initCause(ConnectException("Connection refused")) },
                            )
                            else -> respondingClient("second")
                        }
                    },
                )

                // when
                val result = client.get(URI("https://example.org/anime/23").toURL())

                // then
                assertThat(result.bodyAsString()).isEqualTo("second")
                assertThat(refused).hasSize(1)
            }
        }
    }

    private fun controllerOf(vararg entries: String) = RotatingProxyNetworkController(
        appConfig = fixedClockConfig(),
        configRegistry = poolOf(*entries),
        cooldown = 0,
    )

    private fun throwingClient(proxy: Proxy, seen: MutableList<Proxy>, failure: Throwable) = object : HttpClient {
        override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse {
            seen.add(proxy)
            throw failure
        }

        override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
        override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
    }

    private fun respondingClient(body: String) = object : HttpClient {
        override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse =
            HttpResponse(200, body.toByteArray())

        override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
        override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
    }

    private fun fixedClockConfig(): Config = object : Config by TestAppConfig {
        override fun clock(): Clock = Clock.fixed(Instant.parse("2026-08-26T20:00:00Z"), UTC)
    }

    private fun poolOf(vararg entries: String): ConfigRegistry = object : ConfigRegistry by TestConfigRegistry {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> list(key: String): List<T> = entries.toList() as List<T>
    }

    private fun maxConnectionChanges(value: Int): ConfigRegistry = object : ConfigRegistry by TestConfigRegistry {
        override fun int(key: String): Int = value
    }

    // Returning nothing leaves the property on its default, which is what a deployment without the
    // key set does.
    private fun defaultConfig(): ConfigRegistry = object : ConfigRegistry by TestConfigRegistry {
        override fun int(key: String): Int? = null
    }
}
