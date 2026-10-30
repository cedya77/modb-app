package io.github.manamiproject.modb.core.httpclient

import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset.UTC
import kotlin.test.Test
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

internal class RetryAfterKtTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-27T20:00:00Z"), UTC)

    @Test
    fun `returns null if the header is missing`() {
        // when
        val result = parseRetryAfter(mapOf("content-type" to listOf("application/json")), clock)

        // then
        assertThat(result).isNull()
    }

    @Test
    fun `reads delay in seconds`() {
        // when
        val result = parseRetryAfter(mapOf("retry-after" to listOf("42")), clock)

        // then
        assertThat(result).isEqualTo(42.seconds)
    }

    @Test
    fun `header name is matched case-insensitive`() {
        // when
        val result = parseRetryAfter(mapOf("Retry-After" to listOf(" 7 ")), clock)

        // then
        assertThat(result).isEqualTo(7.seconds)
    }

    @Test
    fun `reads http date`() {
        // when
        val result = parseRetryAfter(mapOf("retry-after" to listOf("Tue, 27 Oct 2026 20:01:30 GMT")), clock)

        // then
        assertThat(result).isEqualTo(90.seconds)
    }

    @Test
    fun `http date in the past results in zero`() {
        // when
        val result = parseRetryAfter(mapOf("retry-after" to listOf("Tue, 27 Oct 2026 19:59:00 GMT")), clock)

        // then
        assertThat(result).isEqualTo(ZERO)
    }

    @Test
    fun `returns null for a value which can't be read`() {
        // when
        val result = parseRetryAfter(mapOf("retry-after" to listOf("soon")), clock)

        // then
        assertThat(result).isNull()
    }

    @Test
    fun `returns null for a negative number`() {
        // when
        val result = parseRetryAfter(mapOf("retry-after" to listOf("-5")), clock)

        // then
        assertThat(result).isNull()
    }
}
