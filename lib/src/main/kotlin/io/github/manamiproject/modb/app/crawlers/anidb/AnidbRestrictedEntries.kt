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
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * AniDB entries which are 18+ restricted. The website shows no data for them without an account, so in website mode
 * they are skipped on every run. The API serves them, so they don't apply in API mode.
 * @since 1.0.0
 */
interface AnidbRestrictedEntries {

    /**
     * Removes ids which the title dump no longer names. Those are dead entries now.
     * @since 1.0.0
     * @param idsInTitleDump All ids named by the current title dump.
     */
    suspend fun removeAllNotIn(idsInTitleDump: Set<Int>)

    /**
     * @since 1.0.0
     * @return All restricted ids.
     */
    suspend fun all(): Set<Int>

    /**
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
 * Keeps the restricted entries in the download control state directory, one id per line.
 * @since 1.0.0
 * @property appConfig Application specific configuration. Uses [AppConfig] by default.
 */
class DefaultAnidbRestrictedEntries(
    private val appConfig: Config = AppConfig.instance,
): AnidbRestrictedEntries {

    private val access = Mutex()
    private var entries: MutableSet<Int>? = null

    override suspend fun removeAllNotIn(idsInTitleDump: Set<Int>) = update { current ->
        val removed = current.filterNot { it in idsInTitleDump }
        current.removeAll(removed.toSet())

        if (removed.isNotEmpty()) {
            log.info { "Removed [${removed.size}] 18+ [anidb.net] entries which the title dump no longer names." }
        }
    }

    override suspend fun all(): Set<Int> = access.withLock { load().toSet() }

    override suspend fun add(id: Int) = update { current ->
        if (current.add(id)) {
            log.info { "Keeping [anidbId=$id] as 18+ entry, it is skipped on the website from now on." }
        }
    }

    override suspend fun remove(id: Int) = update { current ->
        if (current.remove(id)) {
            log.info { "[anidb.net] serves the 18+ entry [anidbId=$id] now." }
        }
    }

    private suspend fun update(change: (MutableSet<Int>) -> Unit) = access.withLock {
        val current = load()
        val before = current.toSet()
        change(current)

        if (current != before) {
            save(current)
        }
    }

    private suspend fun load(): MutableSet<Int> = withContext(LIMITED_FS) {
        entries?.let { return@withContext it }

        val file = file()
        val loaded = mutableSetOf<Int>()

        if (file.regularFileExists()) {
            file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith(COMMENT) }
                .mapNotNull { it.toIntOrNull() }
                .forEach { loaded.add(it) }
        }

        entries = loaded
        return@withContext loaded
    }

    private suspend fun save(current: Set<Int>) = withContext(LIMITED_FS) {
        val file = file()
        val content = buildString {
            appendLine("$COMMENT AniDB ids which are 18+ restricted and skipped in website mode.")
            current.sorted().forEach { appendLine(it.toString()) }
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
        const val FILE_NAME: String = "anidb-restricted-entries.txt"

        /**
         * Singleton of [DefaultAnidbRestrictedEntries].
         * @since 1.0.0
         */
        val instance: DefaultAnidbRestrictedEntries by lazy { DefaultAnidbRestrictedEntries() }
    }
}
