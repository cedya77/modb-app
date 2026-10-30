package io.github.manamiproject.modb.app.convfiles

import io.github.manamiproject.modb.core.extensions.Directory
import io.github.manamiproject.modb.core.extensions.RegularFile
import io.github.manamiproject.modb.core.extensions.listRegularFiles
import io.github.manamiproject.modb.core.anime.AnimeRaw
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize

/**
 * File suffix used for the files containing the serialized [AnimeRaw] object as intermediate format.
 * @since 1.0.0
 */
const val CONVERTED_FILE_SUFFIX = "conv"

/**
 * Deletes empty converted files. Stopping the app while a file is being converted can leave an empty file behind,
 * which would otherwise count as converted. Without it, the raw file is converted again.
 * @since 1.0.0
 * @receiver Working directory of a metadata provider.
 * @return Number of deleted files.
 */
internal suspend fun Directory.deleteEmptyConvertedFiles(): Int {
    return listRegularFiles("*.$CONVERTED_FILE_SUFFIX")
        .filter { it.fileSize() == 0L }
        .count { it.deleteIfExists() }
}


/**
 * Converts raw files into a generic intermediate format based on [AnimeRaw].
 * @since 1.0.0
 */
interface FileConverter {

    /**
     * Converts all raw files which haven't been converted yet.
     * @since 1.0.0
     */
    suspend fun convertUnconvertedFiles()

    /**
     * Converts a specific raw file to into the generic intermediate format.
     * @since 1.0.0
     * @param file Raw file which is supposed to be converted to the intermediate format.
     */
    suspend fun convertFileToConvFile(file: RegularFile)
}