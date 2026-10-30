package io.github.manamiproject.modb.app.crawlers.anidb

import io.github.manamiproject.modb.anidb.AnidbConfig
import io.github.manamiproject.modb.anidb.AnidbWebsiteConfig
import io.github.manamiproject.modb.anidb.AnidbDownloader.Companion.ANIDB_PENDING_FILE_INDICATOR
import io.github.manamiproject.modb.anidb.AnidbDownloader.Companion.ANIDB_RESTRICTED_INDICATOR
import io.github.manamiproject.modb.anidb.CrawlerDetectedException
import io.github.manamiproject.modb.app.*
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.app.crawlers.IdRangeSelector
import io.github.manamiproject.modb.app.crawlers.anidb.AnidbCrawler.Companion.ANIDB_PENDING_FILE_SUFFIX
import io.github.manamiproject.modb.app.dataset.DeadEntriesAccessor
import io.github.manamiproject.modb.app.network.NetworkController
import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_CPU
import io.github.manamiproject.modb.core.downloader.Downloader
import io.github.manamiproject.modb.core.extensions.*
import io.github.manamiproject.modb.core.httpclient.HttpClient
import io.github.manamiproject.modb.core.httpclient.RetryCase
import io.github.manamiproject.modb.core.httpclient.ThrowableRetryCase
import io.github.manamiproject.modb.test.exceptionExpected
import io.github.manamiproject.modb.test.shouldNotBeInvoked
import io.github.manamiproject.modb.test.tempDirectory
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatNoException
import org.junit.jupiter.api.Nested
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.time.DurationUnit.SECONDS
import kotlin.time.toDuration

internal class AnidbCrawlerTest {

    @Nested
    inner class ConstructorTests {

        @Test
        fun `adds RetryCases for ConnectException, UnknownHostException, NoRouteToHostException and route failures with restarting the NetworkController`() {
            tempDirectory {
                // given
                val cases = mutableListOf<RetryCase>()
                val testHttpClient = object: HttpClient by TestHttpClient {
                    override fun addRetryCases(vararg retryCases: RetryCase): HttpClient {
                        cases.addAll(retryCases)
                        return this
                    }
                }

                var restartInvocations = 0
                val testNetworkController = object: NetworkController by TestNetworkController {
                    override suspend fun restartAsync(): Deferred<Boolean> {
                        restartInvocations++
                        return runBlocking { async { true } }
                    }
                }

                // when
                AnidbCrawler(
                    appConfig = TestAppConfig,
                    metaDataProviderConfig = TestMetaDataProviderConfig,
                    deadEntriesAccess = TestDeadEntriesAccessor,
                    idRangeSelector = TestIdRangeSelectorInt,
                    httpClient = testHttpClient,
                    downloader = TestDownloader,
                    networkController = testNetworkController,
                    pendingEntries = TestAnidbPendingEntries,
                    restrictedEntries = TestAnidbRestrictedEntries,
                    titleDump = { emptySet() },
                )

                // then
                assertThat(cases).hasSize(4)

                val connectException = cases.find { (it as ThrowableRetryCase).retryIf(ConnectException()) }
                assertThat(connectException).isNotNull()
                connectException!!.executeBefore.invoke()
                assertThat(restartInvocations).isEqualTo(1)

                val unknownHostException = cases.find { (it as ThrowableRetryCase).retryIf(UnknownHostException()) }
                assertThat(unknownHostException).isNotNull()
                unknownHostException!!.executeBefore.invoke()
                assertThat(restartInvocations).isEqualTo(2)

                val noRouteToHostException = cases.find { (it as ThrowableRetryCase).retryIf(NoRouteToHostException()) }
                assertThat(noRouteToHostException).isNotNull()
                noRouteToHostException!!.executeBefore.invoke()
                assertThat(restartInvocations).isEqualTo(3)

                val routeFailure = cases.find { (it as ThrowableRetryCase).retryIf(IOException("Unexpected response code for CONNECT: 503")) }
                assertThat(routeFailure).isNotNull()
                assertThat(routeFailure!!.waitDuration(1)).isEqualTo(5.toDuration(SECONDS))
                routeFailure.executeBefore.invoke()
                assertThat(restartInvocations).isEqualTo(4)
            }
        }
    }

    @Nested
    inner class StartTests {

        @Test
        fun `doesn't do anything if the list of IDs is empty`() {
            // given
            val testIdRangeSelector = object : IdRangeSelector<Int> {
                override suspend fun idDownloadList(): List<Int> = emptyList()
            }

            val crawler = AnidbCrawler(
                appConfig = TestAppConfig,
                downloader = TestDownloader,
                deadEntriesAccess = TestDeadEntriesAccessor,
                idRangeSelector = testIdRangeSelector,
                networkController = TestNetworkController,
                pendingEntries = TestAnidbPendingEntries,
                restrictedEntries = TestAnidbRestrictedEntries,
                titleDump = { emptySet() },
            )

            // when
            assertThatNoException().isThrownBy {
                runTest { crawler.start() }
            }
        }

        @Test
        fun `successfully loads multiple entries`() {
            tempDirectory {
                // given
                val testMetaDataProviderConfig = object: MetaDataProviderConfig by AnidbConfig {
                    override fun isTestContext(): Boolean = true
                }

                val testAppConfig = object : Config by TestAppConfig {
                    override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory = tempDir
                }

                val testIdRangeSelector = object: IdRangeSelector<Int> {
                    override suspend fun idDownloadList(): List<Int> = listOf(1535, 23, 1254, 424)
                }

                val successfulEntry = "successfully loaded content for"
                val testAnimeDownloader = object: Downloader by TestDownloader {
                    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String {
                        return "$successfulEntry $id"
                    }
                }

                val crawler = AnidbCrawler(
                    appConfig = testAppConfig,
                    metaDataProviderConfig = testMetaDataProviderConfig,
                    downloader = testAnimeDownloader,
                    deadEntriesAccess = TestDeadEntriesAccessor,
                    idRangeSelector = testIdRangeSelector,
                    networkController = TestNetworkController,
                    pendingEntries = TestAnidbPendingEntries,
                    restrictedEntries = TestAnidbRestrictedEntries,
                    titleDump = { emptySet() },
                )

                // when
                crawler.start()

                // then
                testIdRangeSelector.idDownloadList().forEach { id ->
                    val result = tempDir.resolve("$id.xml").readFile()
                    assertThat(result).isEqualTo("$successfulEntry $id")
                }
            }
        }

        @Test
        fun `moves pending files of earlier versions to the pending entries and skips them`() {
            tempDirectory {
                // given
                val testMetaDataProviderConfig = object: MetaDataProviderConfig by AnidbConfig {
                    override fun isTestContext(): Boolean = true
                }

                val testAppConfig = object : Config by TestAppConfig {
                    override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory = tempDir
                }

                val testIdRangeSelector = object: IdRangeSelector<Int> {
                    override suspend fun idDownloadList(): List<Int> = listOf(1535, 23, 1254, 424)
                }

                val successfulEntry = "successfully loaded content for"
                val testAnimeDownloader = object: Downloader by TestDownloader {
                    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String {
                        return when(id) {
                            "1254" -> shouldNotBeInvoked()
                            else -> "$successfulEntry $id"
                        }
                    }
                }

                ANIDB_PENDING_FILE_INDICATOR.writeToFile(tempDir.resolve("1254.${ANIDB_PENDING_FILE_SUFFIX}"))

                val pending = mutableSetOf<Int>()
                val testPendingEntries = object: AnidbPendingEntries by TestAnidbPendingEntries {
                    override suspend fun add(id: Int) {
                        pending.add(id)
                    }
                    override suspend fun all(): Set<Int> = pending.toSet()
                }

                val crawler = AnidbCrawler(
                    appConfig = testAppConfig,
                    metaDataProviderConfig = testMetaDataProviderConfig,
                    downloader = testAnimeDownloader,
                    deadEntriesAccess = TestDeadEntriesAccessor,
                    idRangeSelector = testIdRangeSelector,
                    networkController = TestNetworkController,
                    pendingEntries = testPendingEntries,
                    restrictedEntries = TestAnidbRestrictedEntries,
                    titleDump = { setOf(1254) },
                )

                // when
                crawler.start()

                // then
                assertThat(pending).containsExactly(1254)
                assertThat(tempDir).isDirectoryContaining { it.fileName() == "1535.xml" }
                assertThat(tempDir).isDirectoryContaining { it.fileName() == "23.xml" }
                assertThat(tempDir).isDirectoryContaining { it.fileName() == "424.xml" }
                assertThat(tempDir).isDirectoryNotContaining { it.fileName() == "1254.xml" }
                assertThat(tempDir).isDirectoryNotContaining { it.fileName() == "1254.${ANIDB_PENDING_FILE_SUFFIX}" }
            }
        }

        @Test
        fun `passes the animeId to the dead entries accessor if the downloader triggers a dead entry`() {
            tempDirectory {
                // given
                val testMetaDataProviderConfig = object: MetaDataProviderConfig by AnidbConfig {
                    override fun isTestContext(): Boolean = true
                }

                val testAppConfig = object : Config by TestAppConfig {
                    override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory = tempDir
                }

                val testIdRangeSelector = object: IdRangeSelector<Int> {
                    override suspend fun idDownloadList(): List<Int> = listOf(1000)
                }

                val testAnimeDownloader = object: Downloader by TestDownloader {
                    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String {
                        onDeadEntry.invoke(id)
                        return EMPTY
                    }
                }

                val invocations = mutableListOf<AnimeId>()
                val testDeadEntriesAccessor = object: DeadEntriesAccessor by TestDeadEntriesAccessor {
                    override suspend fun addDeadEntry(animeId: AnimeId, metaDataProviderConfig: MetaDataProviderConfig) {
                        invocations.add(animeId)
                    }
                }

                val crawler = AnidbCrawler(
                    appConfig = testAppConfig,
                    metaDataProviderConfig = testMetaDataProviderConfig,
                    downloader = testAnimeDownloader,
                    deadEntriesAccess = testDeadEntriesAccessor,
                    idRangeSelector = testIdRangeSelector,
                    networkController = TestNetworkController,
                    pendingEntries = TestAnidbPendingEntries,
                    restrictedEntries = TestAnidbRestrictedEntries,
                    titleDump = { emptySet() },
                )

                // when
                crawler.start()

                // then
                assertThat(tempDir.listDirectoryEntries()).isEmpty()
                assertThat(invocations).containsExactlyInAnyOrder(
                    "1000",
                )
            }
        }

        @Test
        fun `keeps an id which the title dump names as pending entry instead of adding it to the dead entries`() {
            tempDirectory {
                // given
                val testMetaDataProviderConfig = object: MetaDataProviderConfig by AnidbConfig {
                    override fun isTestContext(): Boolean = true
                }

                val testAppConfig = object : Config by TestAppConfig {
                    override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory = tempDir
                }

                val testIdRangeSelector = object: IdRangeSelector<Int> {
                    override suspend fun idDownloadList(): List<Int> = listOf(1000, 1001)
                }

                val testAnimeDownloader = object: Downloader by TestDownloader {
                    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String {
                        onDeadEntry.invoke(id)
                        return EMPTY
                    }
                }

                val deadEntries = mutableListOf<AnimeId>()
                val testDeadEntriesAccessor = object: DeadEntriesAccessor by TestDeadEntriesAccessor {
                    override suspend fun addDeadEntry(animeId: AnimeId, metaDataProviderConfig: MetaDataProviderConfig) {
                        deadEntries.add(animeId)
                    }
                }

                val pending = mutableListOf<Int>()
                val testPendingEntries = object: AnidbPendingEntries by TestAnidbPendingEntries {
                    override suspend fun add(id: Int) {
                        pending.add(id)
                    }
                }

                val crawler = AnidbCrawler(
                    appConfig = testAppConfig,
                    metaDataProviderConfig = testMetaDataProviderConfig,
                    downloader = testAnimeDownloader,
                    deadEntriesAccess = testDeadEntriesAccessor,
                    idRangeSelector = testIdRangeSelector,
                    networkController = TestNetworkController,
                    pendingEntries = testPendingEntries,
                    restrictedEntries = TestAnidbRestrictedEntries,
                    titleDump = { setOf(1000) },
                )

                // when
                crawler.start()

                // then
                assertThat(pending).containsExactly(1000)
                assertThat(deadEntries).containsExactly("1001")
            }
        }

        @Test
        fun `on the website, an entry reported as removed is dead even though the title dump names it`() {
            tempDirectory {
                // given
                val testMetaDataProviderConfig = object: MetaDataProviderConfig by AnidbWebsiteConfig {
                    override fun isTestContext(): Boolean = true
                }

                val testAppConfig = object : Config by TestAppConfig {
                    override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory = tempDir
                }

                val testIdRangeSelector = object: IdRangeSelector<Int> {
                    override suspend fun idDownloadList(): List<Int> = listOf(1000, 1001)
                }

                val testAnimeDownloader = object: Downloader by TestDownloader {
                    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String {
                        onDeadEntry.invoke(id)
                        return EMPTY
                    }
                }

                val deadEntries = mutableListOf<AnimeId>()
                val testDeadEntriesAccessor = object: DeadEntriesAccessor by TestDeadEntriesAccessor {
                    override suspend fun addDeadEntry(animeId: AnimeId, metaDataProviderConfig: MetaDataProviderConfig) {
                        deadEntries.add(animeId)
                    }
                }

                val added = mutableListOf<Int>()
                val removed = mutableListOf<Int>()
                val testPendingEntries = object: AnidbPendingEntries by TestAnidbPendingEntries {
                    override suspend fun add(id: Int) {
                        added.add(id)
                    }
                    override suspend fun remove(id: Int) {
                        removed.add(id)
                    }
                }

                val crawler = AnidbCrawler(
                    appConfig = testAppConfig,
                    metaDataProviderConfig = testMetaDataProviderConfig,
                    downloader = testAnimeDownloader,
                    deadEntriesAccess = testDeadEntriesAccessor,
                    idRangeSelector = testIdRangeSelector,
                    networkController = TestNetworkController,
                    pendingEntries = testPendingEntries,
                    restrictedEntries = TestAnidbRestrictedEntries,
                    titleDump = { setOf(1000) },
                )

                // when
                crawler.start()

                // then
                assertThat(added).isEmpty()
                assertThat(removed).containsExactly(1000, 1001)
                assertThat(deadEntries).containsExactly("1000", "1001")
            }
        }

        @Test
        fun `skips pending entries and only requests those selected for a recheck`() {
            tempDirectory {
                // given
                val testMetaDataProviderConfig = object: MetaDataProviderConfig by AnidbConfig {
                    override fun isTestContext(): Boolean = true
                }

                val testAppConfig = object : Config by TestAppConfig {
                    override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory = tempDir
                }

                val testIdRangeSelector = object: IdRangeSelector<Int> {
                    override suspend fun idDownloadList(): List<Int> = listOf(1, 2, 3, 4)
                }

                val requested = mutableListOf<AnimeId>()
                val testAnimeDownloader = object: Downloader by TestDownloader {
                    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String {
                        requested.add(id)
                        return "content $id"
                    }
                }

                var recheckRequestedFor = -1
                val removed = mutableListOf<Int>()
                val testPendingEntries = object: AnidbPendingEntries by TestAnidbPendingEntries {
                    override suspend fun all(): Set<Int> = setOf(2, 3)
                    override suspend fun selectForRecheck(numberOfEntries: Int): Set<Int> {
                        recheckRequestedFor = numberOfEntries
                        return setOf(3)
                    }
                    override suspend fun remove(id: Int) {
                        removed.add(id)
                    }
                }

                val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                    override fun int(key: String): Int? = when (key) {
                        "modb.app.crawler.deadEntriesToRecheck" -> 1
                        else -> null
                    }
                }

                val crawler = AnidbCrawler(
                    appConfig = testAppConfig,
                    metaDataProviderConfig = testMetaDataProviderConfig,
                    downloader = testAnimeDownloader,
                    deadEntriesAccess = TestDeadEntriesAccessor,
                    idRangeSelector = testIdRangeSelector,
                    networkController = TestNetworkController,
                    pendingEntries = testPendingEntries,
                    restrictedEntries = TestAnidbRestrictedEntries,
                    titleDump = { setOf(1, 2, 3, 4) },
                    configRegistry = testConfigRegistry,
                )

                // when
                crawler.start()

                // then
                assertThat(recheckRequestedFor).isEqualTo(1)
                assertThat(requested).containsExactlyInAnyOrder("1", "3", "4")
                assertThat(removed).contains(3)
            }
        }

        @Test
        fun `directly throws an exception if it's not one of the cases that restart the network controller`() {
            tempDirectory {
                // given
                val testMetaDataProviderConfig = object: MetaDataProviderConfig by AnidbConfig {
                    override fun isTestContext(): Boolean = true
                }

                val testAppConfig = object : Config by TestAppConfig {
                    override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory = tempDir
                }

                val testIdRangeSelector = object: IdRangeSelector<Int> {
                    override suspend fun idDownloadList(): List<Int> = listOf(1535, 23, 1254, 424)
                }

                val testAnimeDownloader = object: Downloader by TestDownloader {
                    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String {
                        throw NullPointerException("junit test")
                    }
                }

                val crawler = AnidbCrawler(
                    appConfig = testAppConfig,
                    metaDataProviderConfig = testMetaDataProviderConfig,
                    downloader = testAnimeDownloader,
                    deadEntriesAccess = TestDeadEntriesAccessor,
                    idRangeSelector = testIdRangeSelector,
                    networkController = TestNetworkController,
                    pendingEntries = TestAnidbPendingEntries,
                    restrictedEntries = TestAnidbRestrictedEntries,
                    titleDump = { emptySet() },
                )

                // when
                val result = exceptionExpected<NullPointerException> {
                    crawler.start()
                }

                // then
                assertThat(result).hasMessage("junit test")
            }
        }

        @Test
        fun `doesn't to anything if downloader returns an empty string`() {
            tempDirectory {
                // given
                val testMetaDataProviderConfig = object: MetaDataProviderConfig by AnidbConfig {
                    override fun isTestContext(): Boolean = true
                }

                val testAppConfig = object : Config by TestAppConfig {
                    override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory = tempDir
                }

                val testIdRangeSelector = object: IdRangeSelector<Int> {
                    override suspend fun idDownloadList(): List<Int> = listOf(1000)
                }

                val testAnimeDownloader = object: Downloader by TestDownloader {
                    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String = EMPTY
                }

                val crawler = AnidbCrawler(
                    appConfig = testAppConfig,
                    metaDataProviderConfig = testMetaDataProviderConfig,
                    downloader = testAnimeDownloader,
                    deadEntriesAccess = TestDeadEntriesAccessor,
                    idRangeSelector = testIdRangeSelector,
                    networkController = TestNetworkController,
                    pendingEntries = TestAnidbPendingEntries,
                    restrictedEntries = TestAnidbRestrictedEntries,
                    titleDump = { emptySet() },
                )

                // when
                crawler.start()

                // then
                assertThat(tempDir.listDirectoryEntries()).isEmpty()
            }
        }

        @Test
        fun `adds an entry whose addition is pending to the pending entries instead of creating a file`() {
            tempDirectory {
                // given
                val testMetaDataProviderConfig = object: MetaDataProviderConfig by AnidbConfig {
                    override fun isTestContext(): Boolean = true
                }

                val testAppConfig = object : Config by TestAppConfig {
                    override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory = tempDir
                }

                val testIdRangeSelector = object: IdRangeSelector<Int> {
                    override suspend fun idDownloadList(): List<Int> = listOf(1000)
                }

                val testAnimeDownloader = object: Downloader by TestDownloader {
                    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String = ANIDB_PENDING_FILE_INDICATOR
                }

                val pending = mutableListOf<Int>()
                val testPendingEntries = object: AnidbPendingEntries by TestAnidbPendingEntries {
                    override suspend fun add(id: Int) {
                        pending.add(id)
                    }
                }

                val crawler = AnidbCrawler(
                    appConfig = testAppConfig,
                    metaDataProviderConfig = testMetaDataProviderConfig,
                    downloader = testAnimeDownloader,
                    deadEntriesAccess = TestDeadEntriesAccessor,
                    idRangeSelector = testIdRangeSelector,
                    networkController = TestNetworkController,
                    pendingEntries = testPendingEntries,
                    restrictedEntries = TestAnidbRestrictedEntries,
                    titleDump = { emptySet() },
                )

                // when
                crawler.start()

                // then
                assertThat(pending).containsExactly(1000)
                assertThat(tempDir.listDirectoryEntries()).isEmpty()
            }
        }

        @Test
        fun `on the website, keeps an 18+ entry in the restricted entries`() {
            tempDirectory {
                // given
                val testMetaDataProviderConfig = object: MetaDataProviderConfig by AnidbWebsiteConfig {
                    override fun isTestContext(): Boolean = true
                }

                val testAppConfig = object : Config by TestAppConfig {
                    override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory = tempDir
                }

                val testIdRangeSelector = object: IdRangeSelector<Int> {
                    override suspend fun idDownloadList(): List<Int> = listOf(3531)
                }

                val testAnimeDownloader = object: Downloader by TestDownloader {
                    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String = ANIDB_RESTRICTED_INDICATOR
                }

                val removedFromPending = mutableListOf<Int>()
                val testPendingEntries = object: AnidbPendingEntries by TestAnidbPendingEntries {
                    override suspend fun remove(id: Int) {
                        removedFromPending.add(id)
                    }
                }

                val restricted = mutableListOf<Int>()
                val testRestrictedEntries = object: AnidbRestrictedEntries by TestAnidbRestrictedEntries {
                    override suspend fun add(id: Int) {
                        restricted.add(id)
                    }
                }

                val crawler = AnidbCrawler(
                    appConfig = testAppConfig,
                    metaDataProviderConfig = testMetaDataProviderConfig,
                    downloader = testAnimeDownloader,
                    deadEntriesAccess = TestDeadEntriesAccessor,
                    idRangeSelector = testIdRangeSelector,
                    networkController = TestNetworkController,
                    pendingEntries = testPendingEntries,
                    restrictedEntries = testRestrictedEntries,
                    titleDump = { setOf(3531) },
                )

                // when
                crawler.start()

                // then
                assertThat(restricted).containsExactly(3531)
                assertThat(removedFromPending).containsExactly(3531)
                assertThat(tempDir.listDirectoryEntries()).isEmpty()
            }
        }

        @Test
        fun `on the website, skips the restricted entries which the title dump still names`() {
            tempDirectory {
                // given
                val testMetaDataProviderConfig = object: MetaDataProviderConfig by AnidbWebsiteConfig {
                    override fun isTestContext(): Boolean = true
                }

                val testAppConfig = object : Config by TestAppConfig {
                    override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory = tempDir
                }

                val testIdRangeSelector = object: IdRangeSelector<Int> {
                    override suspend fun idDownloadList(): List<Int> = listOf(3531, 23)
                }

                val requested = mutableListOf<AnimeId>()
                val testAnimeDownloader = object: Downloader by TestDownloader {
                    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String {
                        requested.add(id)
                        return "content $id"
                    }
                }

                val restricted = mutableSetOf(3531, 999)
                val testRestrictedEntries = object: AnidbRestrictedEntries by TestAnidbRestrictedEntries {
                    override suspend fun all(): Set<Int> = restricted.toSet()
                    override suspend fun removeAllNotIn(idsInTitleDump: Set<Int>) {
                        restricted.retainAll(idsInTitleDump)
                    }
                }

                val crawler = AnidbCrawler(
                    appConfig = testAppConfig,
                    metaDataProviderConfig = testMetaDataProviderConfig,
                    downloader = testAnimeDownloader,
                    deadEntriesAccess = TestDeadEntriesAccessor,
                    idRangeSelector = testIdRangeSelector,
                    networkController = TestNetworkController,
                    pendingEntries = TestAnidbPendingEntries,
                    restrictedEntries = testRestrictedEntries,
                    titleDump = { setOf(3531, 23) },
                )

                // when
                crawler.start()

                // then
                assertThat(requested).containsExactly("23")
                assertThat(restricted).containsExactly(3531)
            }
        }

        @Test
        fun `in API mode, requests restricted entries like any other`() {
            tempDirectory {
                // given
                val testMetaDataProviderConfig = object: MetaDataProviderConfig by AnidbConfig {
                    override fun isTestContext(): Boolean = true
                }

                val testAppConfig = object : Config by TestAppConfig {
                    override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory = tempDir
                }

                val testIdRangeSelector = object: IdRangeSelector<Int> {
                    override suspend fun idDownloadList(): List<Int> = listOf(3531)
                }

                val requested = mutableListOf<AnimeId>()
                val testAnimeDownloader = object: Downloader by TestDownloader {
                    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String {
                        requested.add(id)
                        return "content $id"
                    }
                }

                val removedFromRestricted = mutableListOf<Int>()
                val testRestrictedEntries = object: AnidbRestrictedEntries by TestAnidbRestrictedEntries {
                    override suspend fun all(): Set<Int> = setOf(3531)
                    override suspend fun remove(id: Int) {
                        removedFromRestricted.add(id)
                    }
                }

                val crawler = AnidbCrawler(
                    appConfig = testAppConfig,
                    metaDataProviderConfig = testMetaDataProviderConfig,
                    downloader = testAnimeDownloader,
                    deadEntriesAccess = TestDeadEntriesAccessor,
                    idRangeSelector = testIdRangeSelector,
                    networkController = TestNetworkController,
                    pendingEntries = TestAnidbPendingEntries,
                    restrictedEntries = testRestrictedEntries,
                    titleDump = { setOf(3531) },
                )

                // when
                crawler.start()

                // then
                assertThat(requested).containsExactly("3531")
                assertThat(removedFromRestricted).containsExactly(3531)
            }
        }

        @Test
        fun `initiates a restart of the network controller if a CrawlerDetectedException is thrown`() {
            tempDirectory {
                // given
                val testMetaDataProviderConfig = object: MetaDataProviderConfig by AnidbConfig {
                    override fun isTestContext(): Boolean = true
                }

                val testAppConfig = object : Config by TestAppConfig {
                    override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory = tempDir
                }

                val testIdRangeSelector = object: IdRangeSelector<Int> {
                    override suspend fun idDownloadList(): List<Int> = listOf(1535, 23, 1254, 424)
                }

                var hasBeenInvoked = false
                val testNetworkController = object: NetworkController by TestNetworkController {
                    override suspend fun restartAsync(): Deferred<Boolean> = withContext(LIMITED_CPU) {
                        hasBeenInvoked = true
                        return@withContext async { true }
                    }
                }

                val successfulEntry = "successfully loaded content for"
                val testAnimeDownloader = object: Downloader by TestDownloader {
                    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String {
                        return if (hasBeenInvoked) {
                            "$successfulEntry $id"
                        } else {
                            throw CrawlerDetectedException
                        }
                    }
                }

                val crawler = AnidbCrawler(
                    appConfig = testAppConfig,
                    metaDataProviderConfig = testMetaDataProviderConfig,
                    downloader = testAnimeDownloader,
                    deadEntriesAccess = TestDeadEntriesAccessor,
                    idRangeSelector = testIdRangeSelector,
                    networkController = testNetworkController,
                    pendingEntries = TestAnidbPendingEntries,
                    restrictedEntries = TestAnidbRestrictedEntries,
                    titleDump = { emptySet() },
                )

                // when
                crawler.start()

                // then
                assertThat(hasBeenInvoked).isTrue()
                testIdRangeSelector.idDownloadList().forEach { id ->
                    val result = tempDir.resolve("$id.xml").readFile()
                    assertThat(result).isEqualTo("$successfulEntry $id")
                }
            }
        }

        @Test
        fun `throws an exception if a restart of the network controller didn't help`() {
            tempDirectory {
                // given
                val testMetaDataProviderConfig = object: MetaDataProviderConfig by AnidbConfig {
                    override fun isTestContext(): Boolean = true
                }

                val testAppConfig = object : Config by TestAppConfig {
                    override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory = tempDir
                }

                val testIdRangeSelector = object: IdRangeSelector<Int> {
                    override suspend fun idDownloadList(): List<Int> = listOf(1535, 23, 1254, 424)
                }

                val testAnimeDownloader = object: Downloader by TestDownloader {
                    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String {
                        throw NoRouteToHostException("junit test")
                    }
                }

                val crawler = AnidbCrawler(
                    appConfig = testAppConfig,
                    metaDataProviderConfig = testMetaDataProviderConfig,
                    downloader = testAnimeDownloader,
                    deadEntriesAccess = TestDeadEntriesAccessor,
                    idRangeSelector = testIdRangeSelector,
                    networkController = TestNetworkController,
                    pendingEntries = TestAnidbPendingEntries,
                    restrictedEntries = TestAnidbRestrictedEntries,
                    titleDump = { emptySet() },
                )

                // when
                val result = exceptionExpected<NoRouteToHostException> {
                    crawler.start()
                }

                // then
                assertThat(result).hasMessage("junit test")
            }
        }

        @Test
        fun `passes the animeId to the dead entries accessor if the downloader triggers a dead entry in case a retry was necessary first`() {
            tempDirectory {
                // given
                val testMetaDataProviderConfig = object: MetaDataProviderConfig by AnidbConfig {
                    override fun isTestContext(): Boolean = true
                }

                val testAppConfig = object : Config by TestAppConfig {
                    override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory = tempDir
                }

                val testIdRangeSelector = object: IdRangeSelector<Int> {
                    override suspend fun idDownloadList(): List<Int> = listOf(1000)
                }

                var hasBeenInvoked = false
                val testNetworkController = object: NetworkController by TestNetworkController {
                    override suspend fun restartAsync(): Deferred<Boolean> = withContext(LIMITED_CPU) {
                        hasBeenInvoked = true
                        return@withContext async { true }
                    }
                }

                val testAnimeDownloader = object: Downloader by TestDownloader {
                    override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String {
                        return if (hasBeenInvoked) {
                            onDeadEntry(id)
                            EMPTY
                        } else {
                            throw CrawlerDetectedException
                        }
                    }
                }

                val invocations = mutableListOf<AnimeId>()
                val testDeadEntriesAccessor = object: DeadEntriesAccessor by TestDeadEntriesAccessor {
                    override suspend fun addDeadEntry(animeId: AnimeId, metaDataProviderConfig: MetaDataProviderConfig) {
                        invocations.add(animeId)
                    }
                }

                val crawler = AnidbCrawler(
                    appConfig = testAppConfig,
                    metaDataProviderConfig = testMetaDataProviderConfig,
                    downloader = testAnimeDownloader,
                    deadEntriesAccess = testDeadEntriesAccessor,
                    idRangeSelector = testIdRangeSelector,
                    networkController = testNetworkController,
                    pendingEntries = TestAnidbPendingEntries,
                    restrictedEntries = TestAnidbRestrictedEntries,
                    titleDump = { emptySet() },
                )

                // when
                crawler.start()

                // then
                assertThat(hasBeenInvoked).isTrue()
                assertThat(tempDir.listDirectoryEntries()).isEmpty()
                assertThat(invocations).containsExactlyInAnyOrder(
                    "1000",
                )
            }
        }
    }

    @Nested
    inner class PagesPerConnectionTests {

        private fun crawlerWith(tempDir: Directory, pagesPerConnection: Int?, ids: List<Int>, onRestart: () -> Unit): AnidbCrawler {
            val testMetaDataProviderConfig = object: MetaDataProviderConfig by AnidbConfig {
                override fun isTestContext(): Boolean = true
            }
            val testAppConfig = object : Config by TestAppConfig {
                override fun workingDir(metaDataProviderConfig: MetaDataProviderConfig): Directory = tempDir
            }
            val testIdRangeSelector = object: IdRangeSelector<Int> {
                override suspend fun idDownloadList(): List<Int> = ids
            }
            val testNetworkController = object: NetworkController by TestNetworkController {
                override suspend fun restartAsync(): Deferred<Boolean> = withContext(LIMITED_CPU) {
                    onRestart()
                    return@withContext async { true }
                }
            }
            val testAnimeDownloader = object: Downloader by TestDownloader {
                override suspend fun download(id: AnimeId, onDeadEntry: suspend (AnimeId) -> Unit): String = "content $id"
            }
            val testConfigRegistry = object: ConfigRegistry by TestConfigRegistry {
                override fun int(key: String): Int? = when (key) {
                    "modb.anidb.crawler.pagesPerConnection" -> pagesPerConnection
                    else -> null
                }
            }

            return AnidbCrawler(
                appConfig = testAppConfig,
                metaDataProviderConfig = testMetaDataProviderConfig,
                downloader = testAnimeDownloader,
                deadEntriesAccess = TestDeadEntriesAccessor,
                idRangeSelector = testIdRangeSelector,
                networkController = testNetworkController,
                pendingEntries = TestAnidbPendingEntries,
                restrictedEntries = TestAnidbRestrictedEntries,
                titleDump = { emptySet() },
                configRegistry = testConfigRegistry,
            )
        }

        @Test
        fun `moves to the next connection after the configured number of pages`() {
            tempDirectory {
                // given
                var restarts = 0
                val crawler = crawlerWith(tempDir, 2, listOf(1, 2, 3, 4, 5)) { restarts++ }

                // when
                crawler.start()

                // then
                assertThat(restarts).isEqualTo(2)
                assertThat(tempDir.listDirectoryEntries()).hasSize(5)
            }
        }

        @Test
        fun `stays on one connection by default`() {
            tempDirectory {
                // given
                var restarts = 0
                val crawler = crawlerWith(tempDir, null, listOf(1, 2, 3, 4, 5)) { restarts++ }

                // when
                crawler.start()

                // then
                assertThat(restarts).isZero()
            }
        }
    }

    @Nested
    inner class CompanionObjectTests {

        @Test
        fun `instance property always returns same instance`() {
            tempDirectory {
                // given
                val previous = AnidbCrawler.instance

                // when
                val result = AnidbCrawler.instance

                // then
                assertThat(result).isExactlyInstanceOf(AnidbCrawler::class.java)
                assertThat(result === previous).isTrue()
            }
        }
    }
}