package io.github.manamiproject.kommand

/**
 * Generic command line config.
 * @since 1.0.0
 * @param useSudo Set to `true` if a command should use `sudo`. **Default:** `false`
 * @param sudoPassword Set the password for sudo here.
 * @param dryRun Set to `true` to suppress the execution of the actual command. **Default:** `false`
 * @param showCommandInOutput Set to `true` if the resulting output should contain the original command. **Default:** `false`
 */
public data class CommandLineConfig (
    var useSudo: Boolean = false,
    var sudoPassword: String = "",
    var dryRun: Boolean = false,
    var showCommandInOutput: Boolean = false,
)