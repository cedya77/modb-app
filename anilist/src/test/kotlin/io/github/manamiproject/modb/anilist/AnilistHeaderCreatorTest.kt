package io.github.manamiproject.modb.anilist

import io.github.manamiproject.modb.core.httpclient.RequestBody
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

internal class AnilistHeaderCreatorTest {

    @Test
    fun `creates the headers for the official API`() {
        // given
        val requestBody = RequestBody(
            mediaType = "used media type",
            body = "payload",
        )

        // when
        val result = AnilistHeaderCreator.createAnilistHeaders(
            requestBody = requestBody,
        )

        // then
        assertThat(result).containsOnlyKeys("accept", "content-type")
        assertThat(result["accept"]).isEqualTo(listOf("application/json"))
        assertThat(result["content-type"]).isEqualTo(listOf("used media type"))
    }

    @Test
    fun `doesn't send session cookie or csrf token`() {
        // given
        val requestBody = RequestBody(
            mediaType = "used media type",
            body = "payload",
        )

        // when
        val result = AnilistHeaderCreator.createAnilistHeaders(
            requestBody = requestBody,
        )

        // then
        assertThat(result).doesNotContainKeys("cookie", "x-csrf-token")
    }
}
