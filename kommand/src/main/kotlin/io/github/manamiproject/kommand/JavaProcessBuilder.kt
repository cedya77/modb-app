package io.github.manamiproject.kommand

import io.github.manamiproject.kommand.OperatingSystem.LINUX
import io.github.manamiproject.kommand.OperatingSystem.MAC_OS
import io.github.manamiproject.kommand.exceptions.*
import java.io.IOException

private const val SUDO = "sudo"

/**
 * If you want to execute a command as `sudo` you can pass `command` in [JavaProcessBuilder.executeCmd] either with or
 * without sudo as first element. It's only important that [CommandLineConfig.useSudo] is set to `true`. If you don't
 * provide `sudo` yourself in `command` then it will be added automatically.
 *
 * However it does not work the other way. If [CommandLineConfig.useSudo] is set to `false` and `command` in
 * [JavaProcessBuilder.executeCmd] provides `sudo` as first element an exception will be thrown.
 * @since 1.0.0
 * @throws UnexpectedUsageOfSudo Is thrown if [CommandLineConfig.useSudo] is set to `false`, but first element of `command` is `sudo`.
 * @see CommandExecutor
 */
public class JavaProcessBuilder(
    public override var config: CommandLineConfig = CommandLineConfig(),
): CommandExecutor {

    public constructor(config: CommandLineConfig.()-> Unit): this(CommandLineConfig().apply(config))

    override fun executeCmd(command: List<String>): String {
        if (operatingSystem() !in setOf(MAC_OS, LINUX)) {
            throw UnsupportedOperatingSystemException
        }

        if (isSudoCommand(command) && !config.useSudo) {
            throw UnexpectedUsageOfSudo
        }

        val revisedCommand = if (config.useSudo) {
            buildSudoCommand(command, config)
        } else {
            command
        }

        val process = createProcess(revisedCommand)
        val output = mutableListOf<String>()

        if (config.showCommandInOutput) {
            val prefix = if (config.useSudo && !isSudoCommand(command)) {
                "$ sudo "
            } else {
                "$ "
            }
            output.add(command.joinToString(prefix = prefix, separator = " "))
        }

        if (!config.dryRun) {
            output.addAll(process.inputStream.bufferedReader().readLines())
        }

        if (config.useSudo && output.any { it.lowercase().contains("incorrect password attempt") }) {
            throw IncorrectPasswordAttemptException
        }

        if (config.useSudo && output.any { it.lowercase().contains("permission denied") }) {
            throw PermissionDeniedException(revisedCommand.joinToString(" "))
        }

        return output.joinToString("\n")
    }

    private fun isSudoCommand(command: List<String>): Boolean = command.first().equals(SUDO, true)

    private fun buildSudoCommand(command: List<String>, commandLineConfig: CommandLineConfig): List<String> {
        val password = cleanupPassword(commandLineConfig.sudoPassword)

        val commandWithoutSudo = if (isSudoCommand(command)) {
            command.drop(1)
        } else {
            command
        }

        return mutableListOf("/bin/sh", "-c", "echo $password | $SUDO -S ${commandWithoutSudo.joinToString(" ")}")
    }

    private fun cleanupPassword(password: String): String {
        require(password.isNotBlank()) { "sudo password must not be blank" }
        return password.replace("$", "\\$") // escape char for interpolation/variable usage
    }

    private fun createProcess(command: List<String>): Process {
        return try {
            ProcessBuilder(*command.toTypedArray()).apply {
                redirectErrorStream(true)
            }.start()
        } catch (e: IOException) {
            val commandName = if (isSudoCommand(command)) {
                command[1]
            } else {
                command[0]
            }

            when {
                e.cause?.message == "error=2, No such file or directory" -> throw CommandNotFoundException(commandName)
                e.cause?.message?.contains("permission denied", false) == true -> throw PermissionDeniedException(commandName)
                else -> throw e
            }
        }
    }

    public companion object {
        /**
         * Singleton of [JavaProcessBuilder]
         * @since 1.4.0
         */
        public val instance: JavaProcessBuilder by lazy { JavaProcessBuilder() }
    }
}