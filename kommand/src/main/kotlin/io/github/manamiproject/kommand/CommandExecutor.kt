package io.github.manamiproject.kommand

import io.github.manamiproject.kommand.exceptions.IncorrectPasswordAttemptException
import io.github.manamiproject.kommand.exceptions.PermissionDeniedException
import io.github.manamiproject.kommand.exceptions.UnsupportedOperatingSystemException

/**
 * Executes a cli command.
 * @since 1.0.0
 */
public interface CommandExecutor {

    /**
     * Configuration for the [CommandExecutor] implementation
     * @since 1.1.0
     */
    public var config: CommandLineConfig

    /**
     * Execute a cli command.
     * @since 1.0.0
     * @param command A [List] containing the command as first element followed by the arguments and their values.
     * **Example:**
     * ```
     * listOf("echo", "-n", "hello world")
     * ```
     * @return Returns the console output of the command as [String]
     * @throws UnsupportedOperatingSystemException If the operating system on which the code is executed is neither Linux nor MacOs.
     * @throws IncorrectPasswordAttemptException If you want to execute a command using `sudo`, but the provided password is incorrect.
     * @throws PermissionDeniedException In case a command is executed using `sudo` with the correct password, but permission is denied nevertheless.
     */
    public fun executeCmd(command: List<String>): String
}