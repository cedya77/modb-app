package io.github.manamiproject.kommand

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class CommandLineConfigTest {

    @Test
    fun `sudo is false by default`() {
        // given
        val testConfig = CommandLineConfig()

        // when
        val result = testConfig.useSudo

        // then
        assertThat(result).isFalse()
    }

    @Test
    fun `sudo password is empty by default`() {
        // given
        val testConfig = CommandLineConfig()

        // when
        val result = testConfig.sudoPassword

        // then
        assertThat(result).isEmpty()
    }

    @Test
    fun `dryRun is false by default`() {
        // given
        val testConfig = CommandLineConfig()

        // when
        val result = testConfig.dryRun

        // then
        assertThat(result).isFalse()
    }

    @Test
    fun `showCommandInOutput is false by default`() {
        // given
        val testConfig = CommandLineConfig()

        // when
        val result = testConfig.showCommandInOutput

        // then
        assertThat(result).isFalse()
    }
}