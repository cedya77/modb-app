package io.github.manamiproject.modb.app

import io.github.manamiproject.modb.core.coverage.KoverIgnore
import io.github.manamiproject.modb.serde.json.serializer.DeadEntriesJsonSerializer
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.system.exitProcess

private const val CONFIG_FILE = "config.toml"
private const val CONFIG_LOCATION_KEY = "modb.core.config.location"
private const val TEMPLATE_RESOURCE = "config.example.toml"
private val DEAD_ENTRIES_FILES = listOf("anidb", "anilist", "animenewsnetwork", "kitsu", "myanimelist")

/**
 * Offers to create a `config.toml` from the template if none can be found.
 *
 * Has to run before anything logs: logback determines its log directory from the configuration, and
 * the configuration is read only once, so a file created afterwards would not be seen.
 */
@KoverIgnore
internal fun ensureConfigFileExists() {
    if (configFileExists()) {
        return
    }

    val workingDirectory = Path("").absolute()
    val configFile = workingDirectory.resolve(CONFIG_FILE)

    println("No [$CONFIG_FILE] found in [$workingDirectory].")
    println("Either start the app from the folder containing your [$CONFIG_FILE] or pass its location with -D$CONFIG_LOCATION_KEY=/path/to/$CONFIG_FILE.")
    println()
    println("Create [$configFile] from the default template instead?")
    println("  downloadsDirectory            = [${workingDirectory.resolve("downloads")}]")
    println("  outputDirectory               = [${workingDirectory.resolve("output")}]")
    println("  downloadControlStateDirectory = [${workingDirectory.resolve("dcs")}]")
    println("  disabledCrawlers              = [anidb.net]")
    print("Create it? [y/N]: ")
    System.out.flush()

    val answer = readlnOrNull()?.trim()?.lowercase()

    if (answer != "y" && answer != "yes") {
        println("No config created. Exiting.")
        exitProcess(1)
    }

    createDefaultConfig(workingDirectory, configFile)
    println("Created [$configFile]. Add your AniDB client to it before enabling the anidb.net crawler.")
    println()
}

private fun configFileExists(): Boolean {
    val overriddenLocation = System.getProperty(CONFIG_LOCATION_KEY) ?: System.getenv(CONFIG_LOCATION_KEY)

    // An explicitly given location is the caller's choice. If it is wrong, the config registry reports it.
    if (!overriddenLocation.isNullOrBlank()) {
        return true
    }

    return Path(CONFIG_FILE).exists() || Thread.currentThread().contextClassLoader.getResource(CONFIG_FILE) != null
}

private fun createDefaultConfig(workingDirectory: Path, configFile: Path) {
    val downloads = workingDirectory.resolve("downloads").createDirectories()
    val output = workingDirectory.resolve("output").createDirectories()
    val dcs = workingDirectory.resolve("dcs").createDirectories()

    val template = Thread.currentThread().contextClassLoader.getResourceAsStream(TEMPLATE_RESOURCE)
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("Template [$TEMPLATE_RESOURCE] is missing from the app.")

    val config = template
        .setValue("downloadsDirectory", downloads)
        .setValue("outputDirectory", output)
        .setValue("downloadControlStateDirectory", dcs)

    configFile.writeText(config)

    // The dataset expects a dead entries file for each provider which has one, even on the very first run.
    val deadEntriesDirectory = output.resolve("dead-entries").createDirectories()
    val emptyDeadEntries = runBlocking { DeadEntriesJsonSerializer.instance.serialize(emptyList(), minify = true) }

    DEAD_ENTRIES_FILES
        .map { deadEntriesDirectory.resolve("$it-minified.json") }
        .filterNot { it.exists() }
        .forEach { it.writeText(emptyDeadEntries) }
}

private fun String.setValue(key: String, directory: Path): String {
    val value = directory.toString().replace("\\", "\\\\").replace("\"", "\\\"")
    val line = Regex("""(?m)^(\s*$key\s*=\s*)"[^"]*"""")
    check(line.containsMatchIn(this)) { "Template doesn't contain [$key]." }
    return line.replace(this) { "${it.groupValues[1]}\"$value\"" }
}
