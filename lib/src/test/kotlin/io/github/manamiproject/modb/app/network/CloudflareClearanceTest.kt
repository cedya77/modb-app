package io.github.manamiproject.modb.app.network

import io.github.manamiproject.modb.app.TestConfigRegistry
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.HttpResponse
import io.github.manamiproject.modb.core.httpclient.RequestBody
import io.github.manamiproject.modb.core.httpclient.RetryCase
import io.github.manamiproject.modb.test.shouldNotBeInvoked
import io.github.manamiproject.modb.test.tempDirectory
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import java.net.ConnectException
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset.UTC
import kotlin.test.Test

internal class CloudflareClearanceTest {

    private val solved = """{"status":"ok","solution":{"status":200,"userAgent":"Mozilla/5.0 (X11)","cookies":[{"name":"cf_clearance","value":"abc"}]}}"""
    private val now = Instant.parse("2026-10-11T20:00:00Z")
    private val route = "http://127.0.0.1:8901"

    @Nested
    inner class RefreshTests {

        @Test
        fun `asks the solver to leave media out of the render`() {
            runBlocking {
                // given
                val bodies = mutableListOf<String>()
                val clearance = CloudflareClearance(
                    configRegistry = registry(file = null),
                    httpClient = solver(bodies),
                    hostname = "anidb.net",
                )

                // when
                clearance.refresh(URI("https://anidb.net/anime/23").toURL(), route)

                // then
                assertThat(bodies.single()).contains(""""disableMedia":true""")
                assertThat(bodies.single()).contains(""""proxy":{"url":"$route"}""")
            }
        }

        @Test
        fun `names the challenge solver if it cannot be reached`() {
            runBlocking {
                // given
                val clearance = CloudflareClearance(
                    configRegistry = registry(file = null),
                    httpClient = object: HttpClient {
                        override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
                        override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse {
                            throw ConnectException("Failed to connect to localhost/127.0.0.1:8191")
                        }
                        override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
                    },
                    hostname = "anidb.net",
                )

                // when
                val result = assertThrows<ConnectException> {
                    runBlocking { clearance.refresh(URI("https://anidb.net/anime/23").toURL()) }
                }

                // then
                assertThat(result).hasMessage("Failed to connect to the challenge solver [localhost/127.0.0.1:8191]")
                assertThat(result.cause).isInstanceOf(ConnectException::class.java)
            }
        }

        @Test
        fun `holds a clearance per route`() {
            runBlocking {
                // given
                val clearance = CloudflareClearance(
                    configRegistry = registry(file = null),
                    httpClient = solver(),
                    hostname = "anidb.net",
                )

                // when
                clearance.refresh(URI("https://anidb.net/anime/23").toURL(), route)

                // then
                assertThat(clearance.heldFor(route)).isEqualTo(Clearance("cf_clearance=abc", "Mozilla/5.0 (X11)"))
                assertThat(clearance.heldFor("http://127.0.0.1:8902")).isNull()
                assertThat(clearance.heldFor(null)).isNull()
            }
        }
    }

    @Nested
    inner class StoreTests {

        @Test
        fun `a new instance reuses the clearances a previous one stored`() {
            tempDirectory {
                // given
                val file = tempDir.resolve("clearances.json")
                CloudflareClearance(registry(file), solver(), "anidb.net", fixed(now))
                    .refresh(URI("https://anidb.net/anime/23").toURL(), route)

                // when
                val restarted = CloudflareClearance(registry(file), noSolver(), "anidb.net", fixed(now.plusSeconds(3600)))

                // then
                assertThat(restarted.heldFor(route)).isEqualTo(Clearance("cf_clearance=abc", "Mozilla/5.0 (X11)"))
            }
        }

        @Test
        fun `does not present a clearance older than the configured age`() {
            tempDirectory {
                // given
                val file = tempDir.resolve("clearances.json")
                CloudflareClearance(registry(file, maxAgeHours = 2), solver(), "anidb.net", fixed(now))
                    .refresh(URI("https://anidb.net/anime/23").toURL(), route)

                // when
                val restarted = CloudflareClearance(registry(file, maxAgeHours = 2), noSolver(), "anidb.net", fixed(now.plusSeconds(3 * 3600)))

                // then
                assertThat(restarted.heldFor(route)).isNull()
            }
        }

        @Test
        fun `keeps the entries of other sites when storing`() {
            tempDirectory {
                // given
                val file = tempDir.resolve("clearances.json")
                CloudflareClearance(registry(file), solver(), "anidb.net", fixed(now))
                    .refresh(URI("https://anidb.net/anime/23").toURL(), route)

                // when
                CloudflareClearance(registry(file), solver(), "anime-planet.com", fixed(now))
                    .refresh(URI("https://www.anime-planet.com/anime/death-note").toURL(), route)

                // then
                assertThat(CloudflareClearance(registry(file), noSolver(), "anidb.net", fixed(now)).heldFor(route)).isNotNull()
                assertThat(CloudflareClearance(registry(file), noSolver(), "anime-planet.com", fixed(now)).heldFor(route)).isNotNull()
            }
        }

        @Test
        fun `a discarded clearance is gone for the next instance as well`() {
            tempDirectory {
                // given
                val file = tempDir.resolve("clearances.json")
                val clearance = CloudflareClearance(registry(file), solver(), "anidb.net", fixed(now))
                clearance.refresh(URI("https://anidb.net/anime/23").toURL(), route)

                // when
                clearance.discard(route)

                // then
                assertThat(clearance.heldFor(route)).isNull()
                assertThat(CloudflareClearance(registry(file), noSolver(), "anidb.net", fixed(now)).heldFor(route)).isNull()
            }
        }
    }

    private fun fixed(instant: Instant): Clock = Clock.fixed(instant, UTC)

    private fun registry(file: Path?, maxAgeHours: Int? = null): ConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
        override fun string(key: String): String? = when (key) {
            "modb.app.challenge.solverUrl" -> "http://solver.invalid:8191/v1"
            "modb.app.challenge.clearanceFile" -> file?.toString()
            else -> null
        }
        override fun int(key: String): Int? = when (key) {
            "modb.app.challenge.clearanceMaxAgeHours" -> maxAgeHours
            else -> null
        }
    }

    private fun solver(bodies: MutableList<String> = mutableListOf()) = object: HttpClient {
        override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
        override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse {
            bodies.add(requestBody.body)
            return HttpResponse(200, solved)
        }
        override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
    }

    private fun noSolver() = object: HttpClient {
        override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
        override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
        override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
    }
}
