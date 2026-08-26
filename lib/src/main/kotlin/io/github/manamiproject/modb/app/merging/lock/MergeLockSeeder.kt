package io.github.manamiproject.modb.app.merging.lock

import io.github.manamiproject.modb.core.extensions.Directory
import io.github.manamiproject.modb.core.extensions.RegularFile
import io.github.manamiproject.modb.core.extensions.writeToFile
import io.github.manamiproject.modb.core.json.Json
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import kotlinx.coroutines.runBlocking
import io.github.manamiproject.modb.serde.json.deserializer.DatasetFromJsonLinesInputStreamDeserializer
import io.github.manamiproject.modb.serde.json.deserializer.FromRegularFileDeserializer
import io.github.manamiproject.modb.serde.json.models.Dataset
import java.net.URI
import kotlin.io.path.Path

/**
 * Rebuilds the merge lock file from a published dataset.
 *
 * A merge lock is a set of `source` URIs which belong to the same anime, which is exactly what the
 * `sources` of a dataset entry are. An entry with more than one source is therefore a merge lock that
 * the merging process already agreed on, which makes a published dataset a complete seed for a
 * pipeline that has no download control state yet.
 *
 * Entries with a single source are skipped. They carry no decision worth locking and would only make
 * the file larger.
 * @since 1.0.0
 */
class MergeLockSeeder(
    private val deserializer: FromRegularFileDeserializer<Dataset> = FromRegularFileDeserializer(
        deserializer = DatasetFromJsonLinesInputStreamDeserializer(),
    ),
) {

    /**
     * Reads a dataset file and writes `merge.lock` into the given directory.
     * @since 1.0.0
     * @param dataset Dataset file to read. `json`, `jsonl` and their `zst` variants are supported.
     * @param downloadControlStateDirectory Directory in which `merge.lock` will be created.
     * @return Number of merge locks written.
     * @throws IllegalStateException if the same source URI appears in more than one entry.
     */
    suspend fun seed(dataset: RegularFile, downloadControlStateDirectory: Directory): Int {
        log.info { "Seeding merge locks from [${dataset.toAbsolutePath()}]." }

        val entries = deserializer.deserialize(dataset).data

        val mergeLocks = entries.asSequence()
            .map { anime -> anime.sources }
            .filter { sources -> sources.size > 1 }
            .map { sources -> sources.map(URI::toString).sorted() }
            .sortedBy { it.first() }
            .toList()

        checkForDuplicates(mergeLocks)

        val mergeLockFile = downloadControlStateDirectory.resolve(MERGE_LOCK_FILE_NAME)
        Json.toJson(MergeLockSeedFile(mergeLocks)).writeToFile(mergeLockFile, true)

        log.info { "Wrote [${mergeLocks.size}] merge locks covering [${mergeLocks.sumOf { it.size }}] sources to [$mergeLockFile]." }

        return mergeLocks.size
    }

    private fun checkForDuplicates(mergeLocks: List<List<String>>) {
        val seen = mutableSetOf<String>()
        val duplicates = mutableSetOf<String>()

        mergeLocks.flatten().forEach { uri ->
            if (!seen.add(uri)) {
                duplicates.add(uri)
            }
        }

        check(duplicates.isEmpty()) { "Dataset contains sources which are part of multiple entries: $duplicates" }
    }

    companion object {
        private val log by LoggerDelegate()

        /**
         * Name of the file which [DefaultMergeLockAccessor] expects in the download control state directory.
         * @since 1.0.0
         */
        const val MERGE_LOCK_FILE_NAME = "merge.lock"
    }
}

private data class MergeLockSeedFile(
    val mergeLocks: List<List<String>>,
)

/**
 * Rebuilds `merge.lock` from a published dataset file.
 *
 * Usage: `<dataset-file> <download-control-state-directory>`
 * @since 1.0.0
 */
fun main(args: Array<String>) {
    check(args.size == 2) { "Usage: <dataset-file> <download-control-state-directory>" }

    val numberOfMergeLocks = runBlocking {
        MergeLockSeeder().seed(
            dataset = Path(args[0]),
            downloadControlStateDirectory = Path(args[1]),
        )
    }

    println("Seeded $numberOfMergeLocks merge locks.")
}
