package io.github.manamiproject.modb.app.crawlers.anidb

import io.github.manamiproject.modb.anidb.AnidbConfig
import io.github.manamiproject.modb.anidb.AnidbWebsiteConfig
import io.github.manamiproject.modb.app.TestAppConfig
import io.github.manamiproject.modb.app.TestDeadEntriesAccessor
import io.github.manamiproject.modb.app.TestDownloadControlStateAccessor
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.app.crawlers.anidb.AnidbTitlesDumpHighestIdDetector.Companion.DUMP_FILE_NAME
import io.github.manamiproject.modb.app.dataset.DeadEntriesAccessor
import io.github.manamiproject.modb.app.downloadcontrolstate.DownloadControlStateAccessor
import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.extensions.Directory
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.HttpResponse
import io.github.manamiproject.modb.core.httpclient.RequestBody
import io.github.manamiproject.modb.core.httpclient.RetryCase
import io.github.manamiproject.modb.test.exceptionExpected
import io.github.manamiproject.modb.test.shouldNotBeInvoked
import io.github.manamiproject.modb.test.tempDirectory
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import java.io.IOException
import java.net.URL
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset.UTC
import kotlin.io.path.readBytes
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeBytes
import kotlin.test.Test

internal class AnidbTitlesDumpHighestIdDetectorTest {

    private val now = Instant.parse("2026-10-29T12:00:00Z")
    private val smallDump = """
        # created: whenever
        1|1|x-jat|First
        3|1|x-jat|Third
        3|4|en|Third in english
        5|1|x-jat|Fifth
    """.trimIndent().toByteArray()

    @Nested
    inner class DetectHighestIdTests {

        @Test
        fun `reads the highest id from the gzipped dump`() {
            tempDirectory {
                // given
                val dump = this::class.java.classLoader.getResourceAsStream("crawlers/anidb/anime-titles.dat.gz")!!.readBytes()
                val detector = detector(tempDir, clientReturning(dump))

                // when
                val result = detector.detectHighestId()

                // then
                assertThat(result).isEqualTo(20320)
            }
        }

        @Test
        fun `ignores comments and reads every id`() {
            tempDirectory {
                // given
                val dump = this::class.java.classLoader.getResourceAsStream("crawlers/anidb/anime-titles.dat.gz")!!.readBytes()
                val detector = detector(tempDir, clientReturning(dump))

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
            tempDirectory {
                // given
                val detector = detector(tempDir, clientReturning("# created: whenever\n".toByteArray()))

                // when
                val result = exceptionExpected<IllegalStateException> {
                    detector.detectHighestId()
                }

                // then
                assertThat(result).hasMessage("Title dump did not contain any anime id.")
            }
        }

        @Test
        fun `every id up to the highest id which the dump doesn't name becomes a dead entry`() {
            tempDirectory {
                // given
                var replacedWith = emptySet<AnimeId>()
                var replacedFor: MetaDataProviderConfig? = null
                val testDeadEntriesAccessor = object: DeadEntriesAccessor by TestDeadEntriesAccessor {
                    override suspend fun replaceDeadEntries(animeIds: Set<AnimeId>, metaDataProviderConfig: MetaDataProviderConfig) {
                        replacedWith = animeIds
                        replacedFor = metaDataProviderConfig
                    }
                }

                val detector = detector(tempDir, clientReturning(smallDump), deadEntriesAccessor = testDeadEntriesAccessor)

                // when
                val result = detector.detectHighestId()

                // then
                assertThat(result).isEqualTo(5)
                assertThat(replacedWith).containsExactlyInAnyOrder("2", "4")
                assertThat(replacedFor).isEqualTo(AnidbConfig)
            }
        }

        @Test
        fun `keeps the dead entries under the website config when the website is the source`() {
            tempDirectory {
                // given
                var replacedWith = emptySet<AnimeId>()
                var replacedFor: MetaDataProviderConfig? = null
                val testDeadEntriesAccessor = object: DeadEntriesAccessor by TestDeadEntriesAccessor {
                    override suspend fun replaceDeadEntries(animeIds: Set<AnimeId>, metaDataProviderConfig: MetaDataProviderConfig) {
                        replacedWith = animeIds
                        replacedFor = metaDataProviderConfig
                    }
                }

                val detector = detector(
                    tempDir = tempDir,
                    httpClient = clientReturning(smallDump),
                    deadEntriesAccessor = testDeadEntriesAccessor,
                    metaDataProviderConfig = AnidbWebsiteConfig,
                )

                // when
                detector.detectHighestId()

                // then
                assertThat(replacedWith).containsExactlyInAnyOrder("2", "4")
                assertThat(replacedFor).isEqualTo(AnidbWebsiteConfig)
            }
        }

        @Test
        fun `doesn't touch the dead entries if the dump ends below the highest id already in the dataset`() {
            tempDirectory {
                // given
                val testDeadEntriesAccessor = object: DeadEntriesAccessor by TestDeadEntriesAccessor {
                    override suspend fun replaceDeadEntries(animeIds: Set<AnimeId>, metaDataProviderConfig: MetaDataProviderConfig) = shouldNotBeInvoked()
                }

                val detector = detector(
                    tempDir = tempDir,
                    httpClient = clientReturning(smallDump),
                    deadEntriesAccessor = testDeadEntriesAccessor,
                    highestIdAlreadyInDataset = 6,
                )

                // when
                val result = detector.detectHighestId()

                // then
                assertThat(result).isEqualTo(5)
            }
        }
    }

    @Nested
    inner class DownloadOncePerDayTests {

        @Test
        fun `keeps the downloaded dump in the download control state directory`() {
            tempDirectory {
                // given
                val detector = detector(tempDir, clientReturning(smallDump))

                // when
                detector.allIds()

                // then
                assertThat(tempDir.resolve(DUMP_FILE_NAME).readBytes()).isEqualTo(smallDump)
            }
        }

        @Test
        fun `uses the kept dump without downloading if it is younger than one day`() {
            tempDirectory {
                // given
                val file = tempDir.resolve(DUMP_FILE_NAME)
                file.writeBytes(smallDump)
                file.setLastModifiedTime(FileTime.from(now.minus(Duration.ofHours(23))))

                val detector = detector(tempDir, notInvokedClient())

                // when
                val result = detector.allIds()

                // then
                assertThat(result).containsExactlyInAnyOrder(1, 3, 5)
            }
        }

        @Test
        fun `downloads again if the kept dump is one day old`() {
            tempDirectory {
                // given
                val file = tempDir.resolve(DUMP_FILE_NAME)
                file.writeBytes(smallDump)
                file.setLastModifiedTime(FileTime.from(now.minus(Duration.ofDays(1))))

                val newDump = "1|1|x-jat|First\n7|1|x-jat|Seventh\n".toByteArray()
                val detector = detector(tempDir, clientReturning(newDump))

                // when
                val result = detector.allIds()

                // then
                assertThat(result).containsExactlyInAnyOrder(1, 7)
                assertThat(file.readBytes()).isEqualTo(newDump)
            }
        }

        @Test
        fun `uses the kept dump if the download fails`() {
            tempDirectory {
                // given
                val file = tempDir.resolve(DUMP_FILE_NAME)
                file.writeBytes(smallDump)
                file.setLastModifiedTime(FileTime.from(now.minus(Duration.ofDays(3))))

                val detector = detector(tempDir, failingClient())

                // when
                val result = detector.allIds()

                // then
                assertThat(result).containsExactlyInAnyOrder(1, 3, 5)
            }
        }

        @Test
        fun `doesn't replace the kept dump with a download without ids`() {
            tempDirectory {
                // given
                val file = tempDir.resolve(DUMP_FILE_NAME)
                file.writeBytes(smallDump)
                file.setLastModifiedTime(FileTime.from(now.minus(Duration.ofDays(3))))

                val detector = detector(tempDir, clientReturning("<html>maintenance</html>".toByteArray()))

                // when
                val result = detector.allIds()

                // then
                assertThat(result).containsExactlyInAnyOrder(1, 3, 5)
                assertThat(file.readBytes()).isEqualTo(smallDump)
            }
        }

        @Test
        fun `throws if the download fails and there is no kept dump`() {
            tempDirectory {
                // given
                val detector = detector(tempDir, failingClient())

                // when
                val result = exceptionExpected<IOException> {
                    detector.allIds()
                }

                // then
                assertThat(result).hasMessage("unreachable")
            }
        }
    }

    private fun detector(
        tempDir: Directory,
        httpClient: HttpClient,
        deadEntriesAccessor: DeadEntriesAccessor = object: DeadEntriesAccessor by TestDeadEntriesAccessor {
            override suspend fun replaceDeadEntries(animeIds: Set<AnimeId>, metaDataProviderConfig: MetaDataProviderConfig) { }
        },
        highestIdAlreadyInDataset: Int = 0,
        metaDataProviderConfig: MetaDataProviderConfig = AnidbConfig,
    ): AnidbTitlesDumpHighestIdDetector {
        val testAppConfig = object: Config by TestAppConfig {
            override fun downloadControlStateDirectory(): Directory = tempDir
            override fun clock(): Clock = Clock.fixed(now, UTC)
        }

        val testDownloadControlStateAccessor = object: DownloadControlStateAccessor by TestDownloadControlStateAccessor {
            override suspend fun highestIdAlreadyInDataset(metaDataProviderConfig: MetaDataProviderConfig): Int = highestIdAlreadyInDataset
        }

        return AnidbTitlesDumpHighestIdDetector(
            appConfig = testAppConfig,
            httpClient = httpClient,
            deadEntriesAccessor = deadEntriesAccessor,
            downloadControlStateAccessor = testDownloadControlStateAccessor,
            metaDataProviderConfig = metaDataProviderConfig,
        )
    }

    private fun clientReturning(body: ByteArray): HttpClient = object: HttpClient {
        override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse {
            return HttpResponse(200, body)
        }
        override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
        override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
    }

    private fun notInvokedClient(): HttpClient = object: HttpClient {
        override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
        override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
        override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
    }

    private fun failingClient(): HttpClient = object: HttpClient {
        override suspend fun get(url: URL, headers: Map<String, Collection<String>>): HttpResponse = throw IOException("unreachable")
        override suspend fun post(url: URL, requestBody: RequestBody, headers: Map<String, Collection<String>>): HttpResponse = shouldNotBeInvoked()
        override fun addRetryCases(vararg retryCases: RetryCase): HttpClient = this
    }
}
