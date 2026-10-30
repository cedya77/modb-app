package io.github.manamiproject.modb.core.httpclient

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

internal class CloudflareChallengeKtTest {

    private val challenge = """<!DOCTYPE html><html><head><title>Just a moment...</title></head><body><script>window._cf_chl_opt={}</script></body></html>"""
    private val content = """<!DOCTYPE html><html><head><title>All Seasons | LiveChart.me</title></head><body><ul class="grouped-list"></ul></body></html>"""

    @Test
    fun `recognises the browser check`() {
        // when
        val result = HttpResponse(403, challenge).isCloudflareChallenge()

        // then
        assertThat(result).isTrue()
    }

    @Test
    fun `recognises the browser check served with 200`() {
        // when
        val result = HttpResponse(200, challenge).isCloudflareChallenge()

        // then
        assertThat(result).isTrue()
    }

    @Test
    fun `recognises the block page`() {
        // when
        val result = HttpResponse(403, "<html><head><title>Attention Required! | Cloudflare</title></head></html>").isCloudflareChallenge()

        // then
        assertThat(result).isTrue()
    }

    @Test
    fun `regular content is no challenge`() {
        // when
        val result = HttpResponse(200, content).isCloudflareChallenge()

        // then
        assertThat(result).isFalse()
    }

    @Test
    fun `an error page of another kind is no challenge`() {
        // when
        val result = HttpResponse(404, challenge).isCloudflareChallenge()

        // then
        assertThat(result).isFalse()
    }

    @Test
    fun `the body can still be read afterwards`() {
        // given
        val response = HttpResponse(200, content)

        // when
        response.isCloudflareChallenge()

        // then
        assertThat(response.bodyAsString()).isEqualTo(content)
    }

    @Test
    fun `the retry case applies to a challenge only`() {
        // then
        assertThat(CloudflareChallengeRetryCase.retryIf(HttpResponse(403, challenge))).isTrue()
        assertThat(CloudflareChallengeRetryCase.retryIf(HttpResponse(200, content))).isFalse()
    }
}
