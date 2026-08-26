package io.github.manamiproject.kommand.exceptions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class UnsupportedOperatingSystemExceptionTest {

    @Test
    fun `UnsupportedOperatingSystemException provides correct message`() {
        // when
        val result = assertThrows<UnsupportedOperatingSystemException> {
            throw UnsupportedOperatingSystemException
        }

        // then
        assertThat(result).hasMessage("The operating system is not supported.")
    }
}