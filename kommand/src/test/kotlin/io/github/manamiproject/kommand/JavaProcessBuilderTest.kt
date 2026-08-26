package io.github.manamiproject.kommand

import io.github.manamiproject.kommand.exceptions.CommandNotFoundException
import io.github.manamiproject.kommand.exceptions.IncorrectPasswordAttemptException
import io.github.manamiproject.kommand.exceptions.UnexpectedUsageOfSudo
import io.github.manamiproject.kommand.exceptions.UnsupportedOperatingSystemException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS.LINUX
import org.junit.jupiter.api.condition.OS.MAC
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class JavaProcessBuilderTest {

    @ParameterizedTest
    @ValueSource(strings = ["windows", "solaris"])
    fun `throws exception if the operating system is not supported`(os: String) {
        // given
        val property = "os.name"
        val originalValue = System.getProperty(property)
        System.setProperty(property, os)

        val javaProcessBuilder = JavaProcessBuilder()
        val command = listOf("echo", "this is a test")

        // when
        val result = assertThrows<UnsupportedOperatingSystemException> {
            javaProcessBuilder.executeCmd(command)
        }

        // then
        assertThat(result).hasMessage("The operating system is not supported.")
        System.setProperty(property, originalValue)
    }

    @Test
    fun `throws exception if commandLineConfig is set not to use sudo, but first element of command is sudo`() {
        // given
        val javaProcessBuilder = JavaProcessBuilder {
            useSudo = false
        }
        val command = listOf("sudo", "echo", "this is a test")

        // when
        val result = assertThrows<UnexpectedUsageOfSudo> {
            javaProcessBuilder.executeCmd(command)
        }

        // then
        assertThat(result).hasMessage("Usage of sudo in CommandLineConfig was set to false, but the provided commands contains sudo.")
    }

    @Test
    fun `do nothing if dryRun is used`() {
        // given
        val javaProcessBuilder = JavaProcessBuilder {
            showCommandInOutput = true
            dryRun = true
        }
        val command = listOf("echo", "this is a test")

        // when
        val result = javaProcessBuilder.executeCmd(command)

        // then
        assertThat(result).isEqualTo("""
            $ echo this is a test
        """.trimIndent())
    }

    @Test
    fun `automatically adds sudo to command if it hasn't been added manually`() {
        // given
        val javaProcessBuilder = JavaProcessBuilder {
            useSudo = true
            sudoPassword = "some-value"
            showCommandInOutput = true
            dryRun = true
        }
        val command = listOf("echo", "this is a test")

        // when
        val result = javaProcessBuilder.executeCmd(command)

        // then
        assertThat(result).isEqualTo("""
            $ sudo echo this is a test
        """.trimIndent())
    }

    @Test
    fun `sudo can be part of the command`() {
        // given
        val javaProcessBuilder = JavaProcessBuilder {
            useSudo = true
            sudoPassword = "some-value"
            showCommandInOutput = true
            dryRun = true
        }
        val command = listOf("sudo", "echo", "this is a test")

        // when
        val result = javaProcessBuilder.executeCmd(command)

        // then
        assertThat(result).isEqualTo("""
            $ sudo echo this is a test
        """.trimIndent())
    }

    @Test
    fun `config can be changed`() {
        // given
        val javaProcessBuilder = JavaProcessBuilder {
            showCommandInOutput = true
            dryRun = true
        }
        val command = listOf("echo", "this is a test")
        javaProcessBuilder.executeCmd(command)

        // when
        javaProcessBuilder.config.showCommandInOutput = false

        // then
        assertThat(javaProcessBuilder.config.showCommandInOutput).isFalse()
        assertThat(javaProcessBuilder.executeCmd(command)).isEmpty()
    }

    @Nested
    @EnabledOnOs(LINUX, MAC)
    @DisabledIfEnvironmentVariable(named = "GITHUB_ACTIONS", matches = "true", disabledReason = "Cannot run on github actions")
    inner class TestsActuallyExecutingCommands {

        @Test
        fun `correctly execute command and return the output`() {
            // given
            val javaProcessBuilder = JavaProcessBuilder()
            val command = listOf("echo", "this is a test")

            // when
            val result = javaProcessBuilder.executeCmd(command)

            // then
            assertThat(result).isEqualTo("this is a test")
        }

        @Test
        fun `print out the original command if option is set to TRUE`() {
            // given
            val javaProcessBuilder = JavaProcessBuilder {
                showCommandInOutput = true
            }
            val command = listOf("echo", "this is a test")

            // when
            val result = javaProcessBuilder.executeCmd(command)

            // then
            assertThat(result).isEqualTo("""
                $ echo this is a test
                this is a test
            """.trimIndent())
        }

        @Test
        fun `throws exception if command is not available on the system`() {
            // given
            val javaProcessBuilder = JavaProcessBuilder {
                showCommandInOutput = true
            }
            val command = listOf("any-non-available-command", "this is a test")

            // when
            val result = assertThrows<CommandNotFoundException> {
                javaProcessBuilder.executeCmd(command)
            }

            // then
            assertThat(result).hasMessage("The command [any-non-available-command] is not available on this system.")
        }

        @Test
        fun `throws exception if command with sudo is given an incorrect password`() {
            // given
            val javaProcessBuilder = JavaProcessBuilder {
                useSudo = true
                sudoPassword = "some-incorrect-value"
            }
            val command = listOf("ls")

            // when
            val result = assertThrows<IncorrectPasswordAttemptException> {
                javaProcessBuilder.executeCmd(command)
            }

            // then
            assertThat(result).isInstanceOf(IncorrectPasswordAttemptException.javaClass)
        }
    }

    @Nested
    inner class CompanionObjectTests {

        @Test
        fun `instance property always returns same instance`() {
            // given
            val previous = JavaProcessBuilder.instance

            // when
            val result = JavaProcessBuilder.instance

            // then
            assertThat(result).isExactlyInstanceOf(JavaProcessBuilder::class.java)
            assertThat(result===previous).isTrue()
        }
    }
}