package io.github.manamiproject.modb.app.postprocessors

import io.github.manamiproject.modb.app.TestAppConfig
import io.github.manamiproject.modb.app.TestDeadEntriesAccessor
import io.github.manamiproject.modb.app.TestJsonSerializer
import io.github.manamiproject.modb.app.TestMetaDataProviderConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.app.dataset.DatasetFileType
import io.github.manamiproject.modb.app.dataset.DeadEntriesAccessor
import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.config.MetaDataProviderConfig
import io.github.manamiproject.modb.core.extensions.RegularFile
import io.github.manamiproject.modb.core.extensions.readFile
import io.github.manamiproject.modb.core.extensions.writeToFile
import io.github.manamiproject.modb.serde.json.serializer.JsonSerializer
import io.github.manamiproject.modb.test.tempDirectory
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import kotlin.io.path.exists
import kotlin.test.Test

internal class DeadEntriesFileCreatorPostProcessorTest {

    @Test
    fun `writes the file of a supported provider even though nothing was added`() {
        tempDirectory {
            // given
            val supported = object: MetaDataProviderConfig by TestMetaDataProviderConfig {
                override fun hostname(): Hostname = "example.org"
            }

            val testAppConfig = object: Config by TestAppConfig {
                override fun metaDataProviderConfigurations(): Set<MetaDataProviderConfig> = setOf(supported)
                override fun deadEntriesSupported(metaDataProviderConfig: MetaDataProviderConfig): Boolean = true
            }

            val testFile = tempDir.resolve("example-org-minified.json")
            """{"lastUpdate":"a year ago"}""".writeToFile(testFile)

            val testDeadEntriesAccessor = object: DeadEntriesAccessor by TestDeadEntriesAccessor {
                override fun deadEntriesFile(metaDataProviderConfig: MetaDataProviderConfig, type: DatasetFileType): RegularFile = testFile
                override suspend fun fetchDeadEntries(metaDataProviderConfig: MetaDataProviderConfig): Set<AnimeId> = setOf("1535")
            }

            val postProcessor = DeadEntriesFileCreatorPostProcessor(
                appConfig = testAppConfig,
                deadEntriesAccessor = testDeadEntriesAccessor,
                jsonSerializer = object: JsonSerializer<Collection<AnimeId>> by TestJsonSerializer() {
                    override suspend fun serialize(obj: Collection<AnimeId>, minify: Boolean): String = """{"deadEntries":["${obj.first()}"]}"""
                },
            )

            // when
            val result = runBlocking { postProcessor.process() }

            // then
            assertThat(result).isTrue()
            assertThat(runBlocking { testFile.readFile() }).isEqualTo("""{"deadEntries":["1535"]}""")
        }
    }

    @Test
    fun `ignores a provider which has no dead entries file`() {
        tempDirectory {
            // given
            val unsupported = object: MetaDataProviderConfig by TestMetaDataProviderConfig {
                override fun hostname(): Hostname = "not-supported.io"
            }

            val testAppConfig = object: Config by TestAppConfig {
                override fun metaDataProviderConfigurations(): Set<MetaDataProviderConfig> = setOf(unsupported)
                override fun deadEntriesSupported(metaDataProviderConfig: MetaDataProviderConfig): Boolean = false
            }

            val testFile = tempDir.resolve("not-supported-io-minified.json")

            val testDeadEntriesAccessor = object: DeadEntriesAccessor by TestDeadEntriesAccessor {
                override fun deadEntriesFile(metaDataProviderConfig: MetaDataProviderConfig, type: DatasetFileType): RegularFile = testFile
            }

            val postProcessor = DeadEntriesFileCreatorPostProcessor(
                appConfig = testAppConfig,
                deadEntriesAccessor = testDeadEntriesAccessor,
            )

            // when
            val result = runBlocking { postProcessor.process() }

            // then
            assertThat(result).isTrue()
            assertThat(testFile.exists()).isFalse()
        }
    }

    @Nested
    inner class CompanionObjectTests {

        @Test
        fun `instance property always returns same instance`() {
            tempDirectory {
                // given
                val previous = DeadEntriesFileCreatorPostProcessor.instance

                // when
                val result = DeadEntriesFileCreatorPostProcessor.instance

                // then
                assertThat(result).isExactlyInstanceOf(DeadEntriesFileCreatorPostProcessor::class.java)
                assertThat(result === previous).isTrue()
            }
        }
    }
}
