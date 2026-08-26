package io.github.manamiproject.kommand.exceptions

/**
 * This exception is thrown in case a command is executed using `sudo` with the correct password, but permission is
 * denied nevertheless.
 * @since 1.0.0
 * @param command Name of the command which was supposed to be executed.
 */
public class PermissionDeniedException(command: String): RuntimeException("Permission denied to execute [$command].")