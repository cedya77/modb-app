package io.github.manamiproject.modb.app.crawlers.anidb

import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_FS
import io.github.manamiproject.modb.core.extensions.regularFileExists
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.LocalDate
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * AniDB entries whose addition is still pending approval. The website says so, the API only refuses them, while the
 * title dump already names them.
 *
 * Such an id is neither dead nor downloadable. As a dead entry it would leave the dead entries list with every new
 * title dump and be requested again on every run, only to be refused again. Kept apart, it is skipped until a recheck
 * is asked for, and it leaves the list on its own once it is downloaded or once the title dump no longer names it.
 * @since 1.0.0
 */
interface AnidbPendingEntries {

    /**
     * Removes ids which the title dump no longer names. Those are dead entries now.
     * @since 1.0.0
     * @param idsInTitleDump All ids named by the current title dump.
     */
    suspend fun removeAllNotIn(idsInTitleDump: Set<Int>)

    /**
     * @since 1.0.0
     * @return All pending ids.
     */
    suspend fun all(): Set<Int>

    /**
     * Selects the pending ids which have been checked longest ago and records today as their last check.
     * @since 1.0.0
     * @param numberOfEntries Maximum number of ids to select.
     * @return Ids to request again.
     */
    suspend fun selectForRecheck(numberOfEntries: Int): Set<Int>

    /**
     * Adds an id or, if it is pending already, records today as its last check.
     * @since 1.0.0
     * @param id AniDB id.
     */
    suspend fun add(id: Int)

    /**
     * Removes an id, because it has been downloaded.
     * @since 1.0.0
     * @param id AniDB id.
     */
    suspend fun remove(id: Int)
}

/**
 * Keeps the pending entries in the download control state directory, one `id last-check` pair per line.
 * @since 1.0.0
 * @property appConfig Application specific configuration. Uses [AppConfig] by default.
 */
class DefaultAnidbPendingEntries(
    private val appConfig: Config = AppConfig.instance,
): AnidbPendingEntries {

    private val access = Mutex()
    private var entries: MutableMap<Int, LocalDate>? = null

    override suspend fun removeAllNotIn(idsInTitleDump: Set<Int>) = update { current ->
        val removed = current.keys.filterNot { it in idsInTitleDump }
        removed.forEach { current.remove(it) }

        if (removed.isNotEmpty()) {
            log.info { "Removed [${removed.size}] pending [anidb.net] entries which the title dump no longer names." }
        }
    }

    override suspend fun all(): Set<Int> = access.withLock { load().keys.toSet() }

    override suspend fun selectForRecheck(numberOfEntries: Int): Set<Int> {
        if (numberOfEntries <= 0) {
            return emptySet()
        }

        var selected = emptySet<Int>()

        update { current ->
            selected = current.entries
                .sortedWith(compareBy<Map.Entry<Int, LocalDate>> { it.value }.thenBy { it.key })
                .take(numberOfEntries)
                .map { it.key }
                .toSet()

            val today = LocalDate.now(appConfig.clock())
            selected.forEach { current[it] = today }
        }

        return selected
    }

    override suspend fun add(id: Int) = update { current ->
        if (id !in current) {
            log.info { "Keeping [anidbId=$id] as pending entry, its addition is most likely not approved yet." }
        }

        current[id] = LocalDate.now(appConfig.clock())
    }

    override suspend fun remove(id: Int) = update { current ->
        if (current.remove(id) != null) {
            log.info { "[anidb.net] serves the pending entry [anidbId=$id] now." }
        }
    }

    private suspend fun update(change: (MutableMap<Int, LocalDate>) -> Unit) = access.withLock {
        val current = load()
        val before = current.toMap()
        change(current)

        if (current != before) {
            save(current)
        }
    }

    private suspend fun load(): MutableMap<Int, LocalDate> = withContext(LIMITED_FS) {
        entries?.let { return@withContext it }

        val file = file()
        val loaded = mutableMapOf<Int, LocalDate>()

        if (file.regularFileExists()) {
            file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith(COMMENT) }
                .forEach { line ->
                    val parts = line.split(' ')
                    val id = parts[0].toIntOrNull() ?: return@forEach
                    val lastCheck = parts.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.MIN
                    loaded[id] = lastCheck
                }
        }

        entries = loaded
        return@withContext loaded
    }

    private suspend fun save(current: Map<Int, LocalDate>) = withContext(LIMITED_FS) {
        val file = file()
        val content = buildString {
            appendLine("$COMMENT AniDB ids whose addition is still pending. Format: id last-check")
            current.toSortedMap().forEach { (id, lastCheck) -> appendLine("$id $lastCheck") }
        }

        val tempFile = file.resolveSibling("${file.fileName}.tmp")
        tempFile.writeText(content)
        Files.move(tempFile, file, REPLACE_EXISTING, ATOMIC_MOVE)
    }

    private fun file() = appConfig.downloadControlStateDirectory().resolve(FILE_NAME)

    companion object {
        private val log by LoggerDelegate()
        private const val COMMENT = "#"

        /**
         * Name of the file in the download control state directory.
         * @since 1.0.0
         */
        const val FILE_NAME: String = "anidb-pending-entries.txt"

        /**
         * Singleton of [DefaultAnidbPendingEntries].
         * @since 1.0.0
         */
        val instance: DefaultAnidbPendingEntries by lazy { DefaultAnidbPendingEntries() }
    }
}
