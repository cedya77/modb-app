package io.github.manamiproject.modb.app.postprocessors

import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.app.dataset.DatasetFileType.JSON_MINIFIED
import io.github.manamiproject.modb.app.dataset.DeadEntriesAccessor
import io.github.manamiproject.modb.app.dataset.DefaultDeadEntriesAccessor
import io.github.manamiproject.modb.core.config.AnimeId
import io.github.manamiproject.modb.core.extensions.writeToFile
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import io.github.manamiproject.modb.serde.json.serializer.DeadEntriesJsonSerializer
import io.github.manamiproject.modb.serde.json.serializer.JsonSerializer

/**
 * Writes the dead entries files.
 *
 * They were previously only written when an entry was added to one, which leaves a provider that
 * gained no dead entries publishing whatever it last wrote. The files carry the tag they are
 * published under and the date they were written, so an untouched file names a release it was not
 * part of and a schema which cannot be resolved under the current tag.
 * @since 1.0.0
 * @property appConfig Application specific configuration. Uses [AppConfig] by default.
 * @property deadEntriesAccessor Access to dead entries files.
 * @property jsonSerializer Creates the content of the files.
 */
class DeadEntriesFileCreatorPostProcessor(
    private val appConfig: Config = AppConfig.instance,
    private val deadEntriesAccessor: DeadEntriesAccessor = DefaultDeadEntriesAccessor.instance,
    private val jsonSerializer: JsonSerializer<Collection<AnimeId>> = DeadEntriesJsonSerializer.instance,
): PostProcessor {

    override suspend fun process(): Boolean {
        log.info { "Writing dead entries files." }

        appConfig.metaDataProviderConfigurations()
            .filter { appConfig.deadEntriesSupported(it) }
            .forEach { config ->
                val deadEntries = deadEntriesAccessor.fetchDeadEntries(config)
                jsonSerializer.serialize(deadEntries, minify = true)
                    .writeToFile(deadEntriesAccessor.deadEntriesFile(config, JSON_MINIFIED))
            }

        return true
    }

    companion object {
        private val log by LoggerDelegate()

        /**
         * Singleton of [DeadEntriesFileCreatorPostProcessor]
         * @since 1.0.0
         */
        val instance: DeadEntriesFileCreatorPostProcessor by lazy { DeadEntriesFileCreatorPostProcessor() }
    }
}
