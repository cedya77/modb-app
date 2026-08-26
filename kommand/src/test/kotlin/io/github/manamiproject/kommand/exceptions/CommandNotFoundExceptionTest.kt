package io.github.manamiproject.kommand.exceptions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class CommandNotFoundExceptionTest {

    @Test
    fun `exception message contains the name of the command`() {
        // given
        val command = "failing-command"

        // when
        val result = assertThrows<CommandNotFoundException> {
            throw CommandNotFoundException(command)
        }

        // then
        assertThat(result).hasMessage("The command [failing-command] is not available on this system.")
    }
}