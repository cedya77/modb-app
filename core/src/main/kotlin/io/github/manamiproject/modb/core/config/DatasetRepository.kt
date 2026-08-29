package io.github.manamiproject.modb.core.config

import java.net.URI

/**
 * The repository the dataset is published from.
 *
 * Published files link back to that repository: the schema each entry validates against, the license
 * the data is published under, the repository itself and the placeholder images used for entries
 * which have no picture. A fork publishes its own files under its own tags, so leaving the links
 * pointing elsewhere names a tag which will never exist there and leaves the images it serves
 * depending on a repository it does not control.
 * @since 1.0.0
 */
public object DatasetRepository {

    private val repository: String by StringPropertyDelegate(
        configRegistry = DefaultConfigRegistry.instance,
        namespace = CONFIG_NAMESPACE,
        default = DEFAULT_REPOSITORY,
    )

    private val branch: String by StringPropertyDelegate(
        configRegistry = DefaultConfigRegistry.instance,
        namespace = CONFIG_NAMESPACE,
        default = DEFAULT_BRANCH,
    )

    /**
     * @since 1.0.0
     * @return Address of the repository without a trailing slash.
     */
    public fun url(): String = repository.trimEnd('/')

    /**
     * @since 1.0.0
     * @param tag Tag the file was published under.
     * @param fileName Name of the schema file.
     * @return Address the schema can be read from.
     */
    public fun schema(tag: String, fileName: String): URI = URI("${raw()}/refs/tags/$tag/schemas/$fileName")

    /**
     * @since 1.0.0
     * @param ref Tag or branch to link the license of.
     * @return Address of the license file.
     */
    public fun license(ref: String): URI = URI("${url()}/blob/$ref/LICENSE")

    /**
     * @since 1.0.0
     * @param fileName Name of the image.
     * @return Address the image can be read from. Taken from the branch rather than a tag, because
     * it is the same image in every release.
     */
    public fun picture(fileName: String): URI = URI("${raw()}/$branch/pics/$fileName")

    private fun raw(): String = url().replace("://github.com/", "://raw.githubusercontent.com/")

    private const val DEFAULT_REPOSITORY = "https://github.com/manami-project/anime-offline-database"
    private const val DEFAULT_BRANCH = "master"

    /**
     * Prefix of the properties read by this class.
     * @since 1.0.0
     */
    public const val CONFIG_NAMESPACE: String = "modb.dataset"
}
