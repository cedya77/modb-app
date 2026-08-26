package io.github.manamiproject.kommand.exceptions

/**
 * This exception is thrown if the operating system is not supported.
 * @since 1.0.0
 */
public object UnsupportedOperatingSystemException: RuntimeException("The operating system is not supported.")