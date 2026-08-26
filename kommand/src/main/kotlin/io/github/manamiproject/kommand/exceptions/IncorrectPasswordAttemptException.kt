package io.github.manamiproject.kommand.exceptions

/**
 * This exception is thrown whenever a command needs `sudo` to be executed, but the password provided was wrong.
 * @since 1.0.0
 */
public object IncorrectPasswordAttemptException: RuntimeException()