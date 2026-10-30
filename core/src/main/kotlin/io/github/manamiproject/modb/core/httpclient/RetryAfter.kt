package io.github.manamiproject.modb.core.httpclient

import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the `Retry-After` header of a response (RFC 9110, section 10.2.3).
 * The value is either a number of seconds or an HTTP date.
 * @since 20.0.0
 * @param headers Headers of the response. The header name is matched case-insensitive.
 * @param clock Used to calculate the time until an HTTP date.
 * @return How long to wait before the next request or `null` if the header is missing or can't be read.
 * A date in the past results in [ZERO].
 */
internal fun parseRetryAfter(headers: Map<String, Collection<String>>, clock: Clock = Clock.systemUTC()): Duration? {
    val value = headers.entries
        .firstOrNull { it.key.equals("retry-after", ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        ?.trim()
        ?: return null

    value.toLongOrNull()?.let { secondsToWait ->
        return if (secondsToWait < 0) null else secondsToWait.seconds
    }

    return try {
        val date = ZonedDateTime.parse(value, RFC_1123_DATE_TIME)
        val millisToWait = date.toInstant().toEpochMilli() - clock.millis()
        if (millisToWait <= 0) ZERO else millisToWait.milliseconds
    } catch (e: DateTimeParseException) {
        null
    }
}
