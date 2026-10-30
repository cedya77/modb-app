package io.github.manamiproject.modb.core.httpclient

/**
 * Cloudflare answers a client it doesn't trust (yet) with an interstitial page instead of the content: the browser
 * check "Just a moment..." or the block page "Attention Required!". Depending on the site this comes with 403, 503,
 * 429 or even 200.
 *
 * Reads the body, which [HttpResponse] keeps in memory afterwards, so the caller can still read it.
 * @since 1.0.0
 * @return `true` if the response is one of Cloudflare's interstitial pages rather than the requested content.
 */
public fun HttpResponse.isCloudflareChallenge(): Boolean {
    if (code !in CHALLENGE_CODES) {
        return false
    }

    val body = bodyAsString()
    return CHALLENGE_MARKERS.any { body.contains(it, ignoreCase = true) }
}

/**
 * Retries a request answered with a Cloudflare interstitial page, waiting like any other retry.
 * @since 1.0.0
 */
public val CloudflareChallengeRetryCase: HttpResponseRetryCase = HttpResponseRetryCase { it.isCloudflareChallenge() }

private val CHALLENGE_CODES = setOf(200, 403, 429, 503)

private val CHALLENGE_MARKERS = listOf(
    "<title>Just a moment...</title>",
    "<title>Attention Required! | Cloudflare</title>",
    "window._cf_chl_opt",
)
