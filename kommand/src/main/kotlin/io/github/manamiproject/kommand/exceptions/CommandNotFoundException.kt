package io.github.manamiproject.kommand.exceptions

/**
 * This exception is thrown whenever a command which should be executed doesn't exist.
 * @since 1.0.0
 * @param command Name of the command which was supposed to be executed.
 */
public class CommandNotFoundException(command: String): RuntimeException("The command [$command] is not available on this system.")