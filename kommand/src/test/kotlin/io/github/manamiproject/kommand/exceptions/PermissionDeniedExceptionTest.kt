package io.github.manamiproject.kommand.exceptions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class PermissionDeniedExceptionTest {

    @Test
    fun `exception message contains the name of the command`() {
        // given
        val command = "failing-command"

        // when
        val result = assertThrows<PermissionDeniedException> {
            throw PermissionDeniedException(command)
        }

        // then
        assertThat(result).hasMessage("Permission denied to execute [failing-command].")
    }
}