package io.github.manamiproject.kommand

import io.github.manamiproject.kommand.OperatingSystem.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

private const val PROPERTY_OS_NAME = "os.name"

internal class FunctionsKtTest {

    @Nested
    inner class OperatingSystemsTests {

        @Test
        fun `operating system is linux`() {
            // given
            val originalValue = System.getProperty(PROPERTY_OS_NAME)
            System.setProperty(PROPERTY_OS_NAME, "linux")

            // when
            val result = operatingSystem()

            assertThat(result).isEqualTo(LINUX)
            System.setProperty(PROPERTY_OS_NAME, originalValue)
        }

        @Test
        fun `operating system is macos`() {
            // given
            val originalValue = System.getProperty(PROPERTY_OS_NAME)
            System.setProperty(PROPERTY_OS_NAME, "macos")

            // when
            val result = operatingSystem()

            assertThat(result).isEqualTo(MAC_OS)
            System.setProperty(PROPERTY_OS_NAME, originalValue)
        }

        @Test
        fun `operating system is windows`() {
            // given
            val originalValue = System.getProperty(PROPERTY_OS_NAME)
            System.setProperty(PROPERTY_OS_NAME, "windows")

            // when
            val result = operatingSystem()

            assertThat(result).isEqualTo(WINDOWS)
            System.setProperty(PROPERTY_OS_NAME, originalValue)
        }

        @Test
        fun `operating system is other`() {
            // given
            val originalValue = System.getProperty(PROPERTY_OS_NAME)
            System.setProperty(PROPERTY_OS_NAME, "solaris")

            // when
            val result = operatingSystem()

            assertThat(result).isEqualTo(OTHER)
            System.setProperty(PROPERTY_OS_NAME, originalValue)
        }
    }
}