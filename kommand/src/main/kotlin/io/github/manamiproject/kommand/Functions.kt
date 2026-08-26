package io.github.manamiproject.kommand

/**
 * Retrieves the current operating system.
 * @since 1.0.0
 * @return Value of [OperatingSystem] representing the system the code is executed on.
 */
public fun operatingSystem(): OperatingSystem {
    val osName = System.getProperty("os.name").lowercase()

    return when {
        osName.contains("mac") -> OperatingSystem.MAC_OS
        osName.contains("windows") -> OperatingSystem.WINDOWS
        osName.contains("linux") -> OperatingSystem.LINUX
        else -> OperatingSystem.OTHER
    }
}