package io.github.manamiproject.kommand.exceptions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class UnexpectedUsageOfSudoTest {

    @Test
    fun `UnexpectedUsageOfSudo provides correct message`() {
        // when
        val result = assertThrows<UnexpectedUsageOfSudo> {
            throw UnexpectedUsageOfSudo
        }

        // then
        assertThat(result).hasMessage("Usage of sudo in CommandLineConfig was set to false, but the provided commands contains sudo.")
    }
}