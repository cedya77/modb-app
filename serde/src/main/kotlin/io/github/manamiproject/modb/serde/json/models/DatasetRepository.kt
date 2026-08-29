package io.github.manamiproject.modb.serde.json.models

import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.StringPropertyDelegate
import java.net.URI

/**
 * The repository the dataset is published from.
 *
 * Every file carries links back to that repository: the schema each entry validates against, the
 * license the data is published under and the repository itself. A fork publishes its own files
 * under its own tags, so leaving the links pointing elsewhere names a tag which will never exist
 * there and the schema cannot be resolved.
 * @since 1.0.0
 */
public object DatasetRepository {

    private val repository: String by StringPropertyDelegate(
        configRegistry = DefaultConfigRegistry.instance,
        namespace = CONFIG_NAMESPACE,
        default = DEFAULT_REPOSITORY,
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
    public fun schema(tag: String, fileName: String): URI = URI(
        "${url().replace("://github.com/", "://raw.githubusercontent.com/")}/refs/tags/$tag/schemas/$fileName"
    )

    /**
     * @since 1.0.0
     * @param ref Tag or branch to link the license of.
     * @return Address of the license file.
     */
    public fun license(ref: String): URI = URI("${url()}/blob/$ref/LICENSE")

    private const val DEFAULT_REPOSITORY = "https://github.com/manami-project/anime-offline-database"

    /**
     * Prefix of the properties read by this class.
     * @since 1.0.0
     */
    public const val CONFIG_NAMESPACE: String = "modb.dataset"
}
